# CardPrint

## 项目目标

1. 用针式打印机稳定打印。  
2. 支持纸张方向设置（常见右旋 `90°`）。  
3. 先做定位标定（方向/偏移/死区），再做正式文字打印。  

## 程序入口与使用场景

### 1. 标定程序（Calibrator）

- 入口命令：
  - `python -m cardprint.cli ui calibrator`
- 适用场景：
  - 首次接入新打印机/新纸张。
  - 更换驱动、换纸、换方向后需要重新校准。
  - 调整原点中心、死区、字段模板布局。
- 主要输出：
  - 打印预设 `preset.json`（包含纸张、旋转、原点、死区、字段模板、推荐打印机）。

### 2. 打印程序（Printer）

- 入口命令：
  - `python -m cardprint.cli ui printer`
- 适用场景：
  - 已有预设，进行单条/批量正式打印。
  - 从手工录入、CSV、XLSX 导入数据并打印。
- 主要输入：
  - `preset.json` + 打印数据（UI 输入或文件导入）。

### 3. 在线打印程序（Online Printer）

- 入口命令：
  - `python -m cardprint.cli ui online`
- 适用场景：
  - 需要从远程服务器实时拉取卡片/封面数据后直接打印。
  - 打印与状态回写分离：打印后通过“人工确认页签”手动回写到业务系统（如 QSL 插件）。
- 页面结构：
  - 配置页：卡片/信封预设、鉴权与公共设置；远程接口地址与拉取过滤规则内置固定，站点地址、二维码短路径与请求超时可配置；点击“拉取远程数据源”一次性获取全量源数据。
  - 启动行为：程序启动后会自动读取当前目录 `bridge_config.json` 并回填配置项（密码除外）；若读取失败则自动回退默认配置。
  - 卡片打印页：字段映射使用程序内置规则（不可编辑），基于已拉取源拼接队列、预览、打印（不自动回写）；卡片数据从 `card-records` 读取，并按 `spec.qsoRecordName` 补读 `qso-records` 注入 `qsoInfo`；通联业务制卡的发射地址字段优先使用 `QsoRecord.spec.myQth`，旧预设字段 `qth` 与新预设字段 `my_qth` 均按此规则取值，缺失时才兜底到对方 `qth`；线下换卡业务制卡页支持按 `spec.offlineActivityName` 选择关联活动筛选队列，并按关联活动的 `spec.activityLocation` 填充 QTH；`spec.cardReceived` 为空或缺失时按未收卡处理，打印互斥项默认勾选“请回卡片”。
  - 卡片版本：登录并拉取卡片版本时，版本列表通过公开接口 `/apis/api.qsl-management.bi1kbu.com/v1alpha1/exchange-online/-/station-cards` 获取；本台通信地址仍通过受保护接口读取。若受保护接口返回登录页或 HTML，工具会明确提示认证或权限问题，不再静默显示为空。
  - 卡片二维码：配置页在“站点地址”下方提供二维码短路径输入项，保存到 `bridge_config.json` 的 `qrcode.path_mappings`；新配置默认映射为 `/apis/api.qsl-management.bi1kbu.com/v1alpha1/EYEBALL -> /eyeball`、`/apis/api.qsl-management.bi1kbu.com/v1alpha1/ONLINE_EYEBALL -> /online_eyeball`、`/apis/api.qsl-management.bi1kbu.com/v1alpha1/receipt-public -> /rp`，已有配置中的自定义映射不会被覆盖。通联业务与线上换卡业务制卡二维码统一使用极简签收确认地址 `/rp`，用于缩短内容并减小二维码；线下换卡业务制卡二维码仍使用 `/eyeball`。
  - 极简页面别名：Halo 同时支持 `/eb`、`/oe`、`/rp`，分别对应线下换卡、线上换卡和公开签收，并支持追加卡片 ID。CardPrint 的签收回执二维码默认使用 `/rp`；线下换卡和线上换卡页面映射仍默认使用语义更清晰的 `/eyeball`、`/online_eyeball`。
  - 本台通信地址：登录并拉取卡片版本时仅补齐空白字段，已有本台姓名、电话、邮编、地址不会被远端资料覆盖。
  - 封面打印页：字段映射使用程序内置规则（不可编辑），显示未打包（`envelopePrinted=false`）且卡片版本不是“不发卡”的卡片封面记录，支持按去向类型筛选“全部卡片 / 国内卡片 / 国际卡片”；去向国为空按国内卡片处理，存在去向国内容按国际卡片处理；基于已拉取源拼接队列、预览、打印（不自动回写）。
  - 制卡确认页：从已拉取源生成“未制卡”清单，勾选条目后手动回写 `cardIssued/cardIssuedAt`，时间格式为 `yyyy-MM-dd HH:mm:ss`。
  - 打包确认页：从已拉取源生成“未打包且非不发卡”清单，勾选条目后手动回写 `envelopePrinted`。
  - 补打信封页：位于打包确认后，复用信封预设，直接读取 `address-book-entries` 与 `bureau-entries` 生成独立信封队列；支持按呼号、地址编号或卡片局编号筛选，支持当前行打印、勾选批量打印、全部打印，不回写业务状态。
  - 补打眼球卡片页：独立页签，使用 `bridge_config.json` 中 `presets.eyeball_reprint_card` 保存专用补卡预设；手工输入呼号与日期时间，支持实时取当前日期时间，生成打印行时固定 `cardType=EYEBALL`、固定“发出卡片”状态，并默认勾选“请回卡片”，不读取或回写业务记录。
  - 自定义打印页：位于在线打印窗口最后一个页签，手动选择任意有效 JSON 打印预设，并根据预设 `fields` 与 `ui_schema` 自动生成单条内容输入控件；`fixed_text` 固定文本字段不要求输入。预览随输入更新，点击“打印当前内容”只提交一条打印数据，不读取或回写业务记录。
  - 自定义打印输出设置：不提供打印机与纸张选择，严格使用预设中的 `preferred_printer` 和 `paper.name`；任一缺失时禁止打印。最后一次模板路径保存到 `bridge_config.json` 的 `presets.custom_single_print`。
  - 推荐作业顺序：`配置登录 -> 卡片打印（制卡） -> 制卡确认 -> 打印封面（打包） -> 打包确认`；需要按主数据补打时进入“补打信封”页，需要临时补打一张眼球发出卡片时进入“补打眼球卡片”页，需要脱离业务数据手工打印任意单条内容时进入“自定义打印”页。
  - 队列过滤规则（内置）：卡片打印仅纳入“未制卡（`cardIssued=false`）”记录；封面打印纳入所有“未打包（`envelopePrinted=false`）且卡片版本不是‘不发卡’”的卡片记录，并可按是否存在去向国区分国内/国际；补打信封不按卡片状态过滤。
  - 耗时拉取：卡片、封面、补打信封页面的“重新拉取并生成队列”，以及制卡/打包确认页的“一键拉取并生成待确认清单”使用后台线程执行；页面底部信息区域会显示正在拉取、已完成拉取、远程源数量、生成队列数量与过滤跳过数量。
  - 字段自动换行：字段配置 `print_width_mm（打印宽度）` 后会按宽度自动分行；配置 `print_height_mm（打印高度）` 后会按字段高度限制最大行数，计算时以最后一行字形仍落在字段高度内为准，不再把最后一行之后的行距计入截断条件。
  - 强制换行：文本字段内容中的字面量 `/n` 和真实换行符都表示强制换行，只识别小写 `/n`。横向排版生成下一行；纵向和混合纵向回到字段顶部并在当前列左侧生成下一列。连续 `/n` 保留空行或空列，`max_len（最大长度）` 只计算实际打印字符；二维码字段保持原始内容，不解析换行标记。
  - 文本对齐：`text_align（文本对齐）` 支持 `left（左对齐）`、`center（居中对齐）`、`right（右对齐）`。居中和右对齐以 `print_width_mm（打印宽度）` 为基准，其中居中对齐要求打印宽度大于 `0`；启用 `distribute_align（分散对齐）` 时分散对齐优先。
  - 排版方式：`layout_mode（排版方式）` 支持 `horizontal（横向）`、`vertical（纵向）`、`mixed_vertical（混合纵向）`，默认横向。纵向将全部字形顺时针旋转 `90°` 并从上向下排列；混合纵向仅旋转 ASCII 英文字母和数字，中文及其他字符保持正向并逐字排成一列。旋转后的半角字母和数字按原字形宽度计算紧凑间距，不再使用完整行高。第一列位于字段区域最右侧，内容超过打印高度时自动向左换列，超过打印宽度后截断。
  - 纵向对齐：纵向和混合纵向中的左对齐、居中、右对齐分别表示顶部、垂直居中、底部对齐；分散对齐沿打印高度均匀分布字符。纵向排版要求打印宽度和打印高度均大于 `0`，`digit_raise_ratio（数字上移比例）` 仅在横向排版中生效。
  - 打印边框：`print_border（是否打印边框）` 为 `true` 时，按照字段的 `x_mm（X 坐标）`、`y_mm（Y 坐标）`、`print_width_mm（打印宽度）`、`print_height_mm（打印高度）` 绘制一次细黑色边框；宽高必须都大于 `0`。边框不增加内边距，也不改变文字或二维码的位置。
  - 固定辅助说明：字段模板支持 `fixed_text` 固定文本。标定工具可在字段模板中填写，打印工具和在线打印工具不会要求录入该字段，预览与打印时固定输出该内容，适合打印固定提示语、辅助说明或版面标识。
  - 数字上移：字段模板支持 `digit_raise_ratio` 数字上移比例。建议仅用于呼号字段，常用值为 `0.33` 或 `0.5`，用于让呼号中的数字比字母略高，降低字母与数字混淆。

### 4. CLI 工具（自动化/排障）

- 入口命令：
  - `python -m cardprint.cli ...`
- 适用场景：
  - 枚举打印机/纸张、打印标定页、校验预设、批处理打印、问题定位。

常用命令：

- `python -m cardprint.cli printer list`
- `python -m cardprint.cli printer papers --printer "OKi 5330SC"`
- `python -m cardprint.cli calibrate print-cross --printer "OKi 5330SC" --paper "卡片横向（Nantian）"`
- `python -m cardprint.cli preset validate --preset presets/preset.json`
- `python -m cardprint.cli print run --job jobs/job.json`

## 典型使用流程

1. 进入标定程序，选择打印机和纸张，打印标定页。  
2. 回填实测中心，反复“打印标定效果”直到十字对正。  
3. 设置死区（预览红色半透明区域）并编辑字段模板。  
4. 保存预设。  
5. 进入打印程序，加载预设，导入数据并正式打印。  

## 功能概览

1. CLI 实现全部打印核心能力，基于 `pywin32` 或同类库，禁止先转 PDF/图片。  
2. UI 大致布局：左侧参数区、右侧预览区；UI 全中文（英文数据源需要内置转换字典）。  
3. 标定程序（UI）通过调用 CLI 实现：
   - 打印机枚举、纸张选项枚举。
   - 旋转方向（左旋/右旋）、旋转角度（90/180/270）设置。
   - 打印原点标定（根据标定十字到上边距/左边距实测值计算）。
   - 字段坐标语义：`x_mm/y_mm` 为目标位置；正式打印时会叠加 `origin_offset_mm` 做标定补偿。
   - 死区标定（四周不可打印边距实测，预览红色透明遮罩并禁止放入内容）。
   - 字段模板编辑（字段名、坐标、打印宽高、打印边框、系统字体、字号、斜体、加粗、文本对齐、排版方式、数字上移比例、固定文本等）。
   - 保存打印预设文件。
4. 打印程序（UI）通过调用 CLI + 预设文件实现：
   - 预设读取后，标定参数只读不可编辑。
   - 字段值录入（输入框/下拉/复选框）。
   - 导入 `CSV/XLSX` 批量数据。
   - 预留网络 JSON 数据源（后续插件化实现）。
   - 代入、预览、打印。
5. 标定程序与打印程序必须保证同一预览、同一打印效果。

## 技术方案文档

- [技术实现方案](docs/技术实现方案.md)
- [实施任务清单](docs/实施任务清单.md)
- [阶段测试报告](docs/阶段测试报告.md)

## 快速开始

1. 安装依赖（Windows）：
   - `pip install -e .`
   - 如需 XLSX：`pip install openpyxl`
   - 如需真实打印：`pip install pywin32`
2. 启动（在项目根目录）：
   - `python -m cardprint.cli printer list`
   - `python -m cardprint.cli ui calibrator`
   - `python -m cardprint.cli ui printer`
   - `python -m cardprint.cli ui online`
3. 测试：
   - `python -m pytest -q`
