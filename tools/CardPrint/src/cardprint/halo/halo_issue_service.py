from __future__ import annotations

import base64
import http.cookiejar
import json
import os
import re
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

from cardprint.core.errors import CardPrintError


_CSRF_INPUT_PATTERN = re.compile(r'name="_csrf"\s+value="([^"]+)"')
_PUBLIC_KEY_PATTERN = re.compile(r'const\s+publicKey\s*=\s*"([^"]+)"')


@dataclass(frozen=True)
class _Asn1Node:
    tag: int
    value: bytes
    start: int
    end: int


def _read_asn1_node(data: bytes, offset: int = 0) -> _Asn1Node:
    if offset >= len(data):
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥 DER 解析失败：偏移越界。",
            details={"offset": offset, "length": len(data)},
        )
    tag = data[offset]
    if offset + 1 >= len(data):
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥 DER 解析失败：缺少长度字段。",
            details={"offset": offset},
        )
    length_first = data[offset + 1]
    index = offset + 2
    if length_first < 0x80:
        length = length_first
    else:
        length_size = length_first & 0x7F
        if length_size == 0 or index + length_size > len(data):
            raise CardPrintError(
                code="HALO_LOGIN_KEY_PARSE_ERROR",
                message="公钥 DER 解析失败：长度编码无效。",
                details={"offset": offset, "length_size": length_size},
            )
        length = int.from_bytes(data[index:index + length_size], "big")
        index += length_size
    end = index + length
    if end > len(data):
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥 DER 解析失败：节点长度越界。",
            details={"offset": offset, "node_end": end, "length": len(data)},
        )
    return _Asn1Node(tag=tag, value=data[index:end], start=offset, end=end)


def _iter_asn1_children(data: bytes) -> list[_Asn1Node]:
    nodes: list[_Asn1Node] = []
    offset = 0
    while offset < len(data):
        node = _read_asn1_node(data, offset)
        nodes.append(node)
        offset = node.end
    return nodes


def _parse_rsa_public_key_from_spki(base64_der: str) -> tuple[int, int]:
    try:
        der = base64.b64decode(base64_der)
    except Exception as exc:  # pragma: no cover
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥 Base64 解码失败。",
            details={"error": str(exc)},
        ) from exc

    spki = _read_asn1_node(der, 0)
    if spki.tag != 0x30:
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥格式错误：不是 SEQUENCE。",
            details={"tag": spki.tag},
        )
    children = _iter_asn1_children(spki.value)
    if len(children) < 2 or children[1].tag != 0x03:
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥格式错误：缺少 BIT STRING。",
            details={},
        )
    bit_string = children[1].value
    if not bit_string:
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥格式错误：BIT STRING 为空。",
            details={},
        )
    if bit_string[0] != 0x00:
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥格式错误：BIT STRING 存在未使用位。",
            details={"unused_bits": bit_string[0]},
        )
    rsa_der = bit_string[1:]
    rsa_seq = _read_asn1_node(rsa_der, 0)
    if rsa_seq.tag != 0x30:
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥格式错误：RSA 结构不是 SEQUENCE。",
            details={"tag": rsa_seq.tag},
        )
    rsa_children = _iter_asn1_children(rsa_seq.value)
    if len(rsa_children) < 2 or rsa_children[0].tag != 0x02 or rsa_children[1].tag != 0x02:
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥格式错误：RSA 参数缺失。",
            details={},
        )
    modulus = int.from_bytes(rsa_children[0].value, "big", signed=False)
    exponent = int.from_bytes(rsa_children[1].value, "big", signed=False)
    if modulus <= 0 or exponent <= 0:
        raise CardPrintError(
            code="HALO_LOGIN_KEY_PARSE_ERROR",
            message="公钥参数无效。",
            details={"modulus": modulus, "exponent": exponent},
        )
    return modulus, exponent


def _encrypt_password_jsencrypt_style(public_key_base64_der: str, plain_password: str) -> str:
    modulus, exponent = _parse_rsa_public_key_from_spki(public_key_base64_der)
    message = plain_password.encode("utf-8")
    key_size = (modulus.bit_length() + 7) // 8
    max_message_len = key_size - 11
    if len(message) > max_message_len:
        raise CardPrintError(
            code="HALO_LOGIN_PASSWORD_TOO_LONG",
            message="密码长度超过公钥加密上限。",
            details={"max_length": max_message_len, "actual_length": len(message)},
        )

    padding_len = key_size - len(message) - 3
    padding = bytearray()
    while len(padding) < padding_len:
        needed = padding_len - len(padding)
        block = os.urandom(max(needed * 2, 16))
        padding.extend(byte for byte in block if byte != 0)
    padded = b"\x00\x02" + bytes(padding[:padding_len]) + b"\x00" + message
    encrypted_int = pow(int.from_bytes(padded, "big"), exponent, modulus)
    encrypted_bytes = encrypted_int.to_bytes(key_size, "big")
    return base64.b64encode(encrypted_bytes).decode("ascii")


def _safe_upper(value: Any) -> str:
    return str(value or "").strip().upper()


class HaloIssueReadonlyService:
    def __init__(self, *, base_url: str, timeout_s: float = 20.0) -> None:
        normalized_base_url = str(base_url or "").strip().rstrip("/")
        if not normalized_base_url:
            raise CardPrintError(
                code="HALO_BASE_URL_REQUIRED",
                message="Halo 地址不能为空。",
                details={},
            )
        self.base_url = normalized_base_url
        try:
            self.timeout_s = max(1.0, float(timeout_s))
        except (TypeError, ValueError) as exc:
            raise CardPrintError(
                code="HALO_TIMEOUT_INVALID",
                message="超时时间必须是数字。",
                details={"timeout_s": timeout_s},
            ) from exc
        self.cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookie_jar))

    def _join_url(self, path: str) -> str:
        return urllib.parse.urljoin(self.base_url + "/", path.lstrip("/"))

    def _request_text(
        self,
        *,
        path: str,
        method: str = "GET",
        headers: dict[str, str] | None = None,
        data: bytes | None = None,
    ) -> tuple[str, str]:
        request = urllib.request.Request(
            url=self._join_url(path),
            method=method.upper(),
            headers=headers or {},
            data=data,
        )
        try:
            with self.opener.open(request, timeout=self.timeout_s) as response:
                final_url = response.geturl()
                text = response.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            content = exc.read().decode("utf-8", errors="replace")
            raise CardPrintError(
                code="HALO_HTTP_ERROR",
                message="请求 Halo 接口失败。",
                details={
                    "path": path,
                    "method": method.upper(),
                    "status": exc.code,
                    "response": content,
                },
            ) from exc
        except urllib.error.URLError as exc:
            raise CardPrintError(
                code="HALO_CONNECT_ERROR",
                message="无法连接 Halo 服务。",
                details={"path": path, "method": method.upper(), "error": str(exc)},
            ) from exc
        return final_url, text

    def _request_json(
        self,
        *,
        path: str,
        method: str = "GET",
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        headers = {"Accept": "application/json"}
        body: bytes | None = None
        if payload is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        _, text = self._request_text(path=path, method=method, headers=headers, data=body)
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError as exc:
            raise CardPrintError(
                code="HALO_JSON_PARSE_ERROR",
                message="Halo 返回了非 JSON 数据。",
                details={"path": path, "line": exc.lineno, "column": exc.colno},
            ) from exc
        if not isinstance(parsed, dict):
            raise CardPrintError(
                code="HALO_JSON_PARSE_ERROR",
                message="Halo 返回的 JSON 结构不是对象。",
                details={"path": path, "type": type(parsed).__name__},
            )
        return parsed

    def _list_extensions(self, plural: str) -> list[dict[str, Any]]:
        query = urllib.parse.urlencode(
            {
                "page": 1,
                "size": 1000,
                "sort": "metadata.creationTimestamp,desc",
            }
        )
        data = self._request_json(path=f"/apis/qsl-management.bi1kbu.com/v1alpha1/{plural}?{query}")
        items = data.get("items")
        if not isinstance(items, list):
            return []
        return [item for item in items if isinstance(item, dict)]

    def login(self, *, username: str, password: str) -> dict[str, Any]:
        normalized_username = str(username or "").strip()
        if not normalized_username:
            raise CardPrintError(
                code="HALO_USERNAME_REQUIRED",
                message="用户名不能为空。",
                details={},
            )
        plain_password = str(password or "")
        if not plain_password:
            raise CardPrintError(
                code="HALO_PASSWORD_REQUIRED",
                message="密码不能为空。",
                details={},
            )

        _, login_html = self._request_text(path="/login", method="GET")
        csrf_match = _CSRF_INPUT_PATTERN.search(login_html)
        if not csrf_match:
            raise CardPrintError(
                code="HALO_LOGIN_CSRF_MISSING",
                message="未在登录页找到 _csrf 字段。",
                details={},
            )
        csrf_token = csrf_match.group(1)

        public_key = ""
        public_key_match = _PUBLIC_KEY_PATTERN.search(login_html)
        if public_key_match:
            # 页面脚本可能包含 \/ 转义，这里解码为标准 Base64 文本。
            public_key = public_key_match.group(1).replace("\\/", "/").strip()
        if not public_key:
            public_key_payload = self._request_json(path="/login/public-key", method="GET")
            public_key = str(public_key_payload.get("base64Format", "")).strip()
        if not public_key:
            raise CardPrintError(
                code="HALO_LOGIN_KEY_MISSING",
                message="未获取到登录公钥。",
                details={},
            )

        encrypted_password = _encrypt_password_jsencrypt_style(public_key, plain_password)
        form_data = urllib.parse.urlencode(
            {
                "username": normalized_username,
                "password": encrypted_password,
                "_csrf": csrf_token,
            }
        ).encode("utf-8")

        final_url, _ = self._request_text(
            path="/login",
            method="POST",
            headers={"Content-Type": "application/x-www-form-urlencoded; charset=utf-8"},
            data=form_data,
        )
        parsed_final = urllib.parse.urlparse(final_url)
        if parsed_final.path.startswith("/login"):
            query = urllib.parse.parse_qs(parsed_final.query)
            error_code = str((query.get("error") or [""])[0])
            if not error_code:
                error_code = "unknown"
            raise CardPrintError(
                code="HALO_LOGIN_FAILED",
                message="登录失败，请检查账号或密码。",
                details={"error": error_code, "url": final_url},
            )

        self._request_json(path="/apis/api.console.halo.run/v1alpha1/users/-")
        return {
            "base_url": self.base_url,
            "username": normalized_username,
        }

    def fetch_issue_readonly(self, *, username: str, password: str) -> dict[str, Any]:
        login_info = self.login(username=username, password=password)

        card_records = self._list_extensions("card-records")
        qso_records = self._list_extensions("qso-records")
        address_entries = self._list_extensions("address-book-entries")
        bureau_entries = self._list_extensions("bureau-entries")

        qso_index: dict[str, dict[str, Any]] = {}
        for qso in qso_records:
            metadata = qso.get("metadata") or {}
            qso_name = str(metadata.get("name", "")).strip()
            if qso_name:
                qso_index[qso_name] = qso

        address_index: dict[str, dict[str, Any]] = {}
        for address in address_entries:
            metadata = address.get("metadata") or {}
            name = str(metadata.get("name", "")).strip()
            if name:
                address_index[name] = address

        bureau_index: dict[str, dict[str, Any]] = {}
        for bureau in bureau_entries:
            metadata = bureau.get("metadata") or {}
            name = str(metadata.get("name", "")).strip()
            if name:
                bureau_index[name] = bureau

        pending_rows: list[dict[str, Any]] = []
        for card in card_records:
            metadata = card.get("metadata") or {}
            if metadata.get("deletionTimestamp"):
                continue
            spec = card.get("spec") or {}
            if bool(spec.get("cardIssued")):
                continue

            card_id = str(metadata.get("name", "")).strip()
            qso_name = str(spec.get("qsoRecordName", "")).strip()
            address_name = str(spec.get("addressEntryName", "")).strip()
            related_qso = qso_index.get(qso_name) if qso_name else None
            related_address = address_index.get(address_name) if address_name else None
            related_bureau = bureau_index.get(address_name) if address_name else None

            row: dict[str, Any] = {
                "cardId": card_id,
                "cardInfo": {
                    "metadata": metadata,
                    "spec": spec,
                    "status": card.get("status") or {},
                },
                "qsoInfo": related_qso,
                "addressInfo": related_address,
                "bureauInfo": related_bureau,
            }
            pending_rows.append(row)

        pending_rows.sort(
            key=lambda item: (
                _safe_upper(item.get("cardInfo", {}).get("spec", {}).get("callSign")),
                _safe_upper(item.get("cardId")),
            )
        )

        return {
            "login": login_info,
            "summary": {
                "pendingCardCount": len(pending_rows),
                "qsoCount": len(qso_records),
                "addressCount": len(address_entries),
                "bureauCount": len(bureau_entries),
            },
            "pendingIssueRows": pending_rows,
        }
