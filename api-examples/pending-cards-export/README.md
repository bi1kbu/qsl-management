# 待发卡片导出接口示例

本示例演示如何通过 Halo + QSL 插件接口完成鉴权，并导出所有待发卡片（`sentStatus=NOT_SENT`）到 CSV。

## 目录

- `export_pending_cards.py`：主程序
- `requirements.txt`：依赖

## 运行前提

1. Halo 站点可访问（默认 `http://localhost:8090`）
2. QSL 插件已启用
3. 具备访问 `/apis/qsl.admin/v1/qsl-card-records` 的账号

## 安装依赖

```bash
pip install -r requirements.txt
```

## 用法

### 方式一：Basic Auth（默认）

```bash
python export_pending_cards.py --username admin --password admin
```

### 方式二：Bearer Token

```bash
python export_pending_cards.py --auth bearer --token <YOUR_TOKEN>
```

也支持环境变量：

```bash
set HALO_TOKEN=<YOUR_TOKEN>
python export_pending_cards.py --auth bearer
```

## 常用参数

- `--base-url`：站点地址，默认 `http://localhost:8090`
- `--output`：输出 CSV 路径，默认 `pending_cards.csv`
- `--operator`：`X-Operator` 请求头，默认 `admin`
- `--include-deleted`：包含逻辑删除记录（默认不包含）

## 输出说明

- 输出编码：`UTF-8 with BOM`（便于 Excel 打开）
- 仅导出待发卡片：`sentStatus == NOT_SENT`
- 表头会自动汇总所有待发记录出现过的字段并展开成 CSV 列
