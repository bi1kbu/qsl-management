package com.bi1kbu.qslmanagement.api;

public final class QslAiPromptDefaults {

    public static final String SYSTEM_PROMPT = """
        你是 QSL 管理系统的数据清洗助手。只输出符合要求的 JSON，不输出解释。
        必须遵守当前请求提供的 JSON Schema，不得改变返回结构、字段名称、字段类型和枚举值。
        """.trim();

    public static final String ONLINE_IMPORT_PROMPT = """
        请从以下线上换卡导入文本中解析一条或多条记录。要求：
        1. 识别呼号、收件人、电话、邮箱、地址、邮编、卡片版本。
        2. 地址整理为“省 市 区 详细地址”的单行格式。
        3. status 只能为“待双方寄出”或“对方已寄出，待我签收”，无法判断时使用“待双方寄出”。
        4. 未写卡片版本时使用默认卡片版本：“{defaultCardVersion}”。
        5. 必须使用系统指定的 JSON Schema 返回，顶层字段为 rows。
        模式：{mode}
        文本：
        {text}
        """.trim();

    public static final String ADDRESS_CLEANUP_PROMPT = """
        请整理以下收件地址。要求：
        1. 每条地址整理为“省 市 区 详细地址”的单行格式，缺失区县时保留可判断的省市和详细地址。
        2. 不要编造姓名、电话、邮编、邮箱，不要修改呼号。
        3. 必须使用系统指定的 JSON Schema 返回，顶层字段为 items。
        输入：
        {rows}
        """.trim();

    public static final String CALLBOOK_ADDRESS_PROMPT = """
        请从以下呼号查询页面或官方接口返回内容中解析通信地址资料。要求：
        1. 必须只输出一个 JSON 对象，不得输出解释、Markdown 或额外文字。
        2. 返回字段名必须固定为英文，且必须包含全部字段：callSign、recipientName、telephone、postalCode、address、email、confidence、message。
        3. 字段含义：
           - callSign：输入呼号的大写形式。
           - recipientName：收件人姓名；没有姓名但有呼号时使用呼号。
           - telephone：电话或手机；没有则为空字符串。
           - postalCode：邮编或 ZIP；没有则为空字符串。
           - address：通信地址，必须是单行字符串。
           - email：邮箱；没有则为空字符串。
           - confidence：0 到 1 的数字，表示解析可信度。
           - message：简短说明数据来源、缺失项或不确定项。
        4. 只提取输入内容中明确存在的信息，不要编造姓名、电话、邮箱、邮编或地址。
        5. 地址整理规则：中文地址优先整理为“省 市 区 详细地址”；国外地址保留原始国家/地区可识别的单行通信地址。
        6. 如果来源是 QRZ.COM 官方 XML，字段优先级如下：
           - callSign 取 call。
           - recipientName 优先取 name_fmt，其次取 fname + name；都没有时取输入呼号。
           - postalCode 取 zip。
           - email 取 email。
           - address 必须优先包含最详细地址字段 addr1；再按需要补充 addr2、state、country。若 addr1 已经是完整中文地址，不要退化为只返回 country、state 或 addr2。
        7. 如果来源是 QRZ.CN 页面预处理，图片文字线索中的文件名、alt、title 可视为页面明确存在的信息；但仍不得编造。
        8. 不得使用“呼号、姓名、电话、邮编、地址、邮箱、置信度、说明”等中文字段名。
        来源：{provider}
        呼号：{callSign}
        内容：
        {features}
        """.trim();

    private QslAiPromptDefaults() {
    }
}
