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
        1. 只提取输入内容中明确存在的信息，不要编造姓名、电话、邮箱、邮编或地址。
        2. 地址整理为“省 市 区 详细地址”或原始国家/地区可识别的单行通信地址。
        3. callSign 使用输入呼号的大写形式。
        4. confidence 使用 0 到 1 的数字表示解析可信度。
        5. 必须使用系统指定的 JSON Schema 返回，不得输出解释。
        6. 返回字段名必须固定为英文：callSign、recipientName、telephone、postalCode、address、email、confidence、message。
        7. 不得使用“呼号、姓名、电话、邮编、地址、邮箱、置信度、说明”等中文字段名。
        来源：{provider}
        呼号：{callSign}
        内容：
        {features}
        """.trim();

    private QslAiPromptDefaults() {
    }
}
