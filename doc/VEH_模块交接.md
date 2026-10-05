# veh 域口径交接册

接手这一片只看这一份：每样事归哪一处作答、状态怎么数、旧账翻哪一本，都在下面。
改口径时只改"唯一入口"那一列指到的方法，屏上/控制器里不许另抄第二套。

库：`chen_023`，建表脚本 `doc/schema/veh.sql`（列名与 `model/auto` 实体逐列对齐，改列两边一起改）。

## 1. 运行界次判定线（判档）

- 表：`t_veh_run_line`（界册）；`t_veh_run_line_verdict`（裁决留痕，判完即归档在册）。
- 唯一裁决口：`TVehRunLineServiceImpl.verdict(measureKind, rawValue, at)`，HTTP 一道门 `GET /vehRunLine/verdict`。
  折算、请界、说话次序、归层、归档全在这一处，控制器只递话。
- 取数折算（只往界册那一头折，取数比数同一把 HALF_UP、同到三位小数）：
  - 车速：车上报 m/s ×3.6 → km/h；
  - 连续驾驶时长：车上报分钟 ÷60 → 小时。
- 量程门（在请界之前）：车速 0..100 m/s、时长 0..1440 分钟；越界（含负值）判出档位，
  结论写"超出量程"，层与代号留空，不许塞进立案层。
- 在位口径（起用含当日、交班不含当日；交班栏空着的存量界认作一直在位；
  已停用、分界数缺格/没递增的不参与）：`isEffective` 一处，
  裁决、`/available` 名单、旧办法 `usable/listAvailable/countAvailable`、屏挂核对同用它。
- 说话次序：`priority` 大者先说话；相同再比 `rule_code` 字典序，落在末位的胜出（确定性，不许两次给不同档）。
- 归层（等号压线归本层，新旧两路同一个说法）：
  `[th1,th2)` 提示=1、`[th2,th3)` 严管=2、`[th3,…)` 立案=3；没到 th1 给"未到起算"、层留空；
  那一刻一条在位界都没有给"无可用界"，代号与起用日两栏留空。
- 补判旧账：`at` 传旧账自己的那一刻，请的是那一刻在位的界，不许拿最新界顶替。
- 归档：每次 `verdict` 都把说话界的代号、界名、起用日、th1/th2/th3、单位、次序、结论、折后数
  快照写入 `t_veh_run_line_verdict`。**日后追溯以归档这一份为准**，界册后来改了也不重算旧账。
  翻阅：`GET /vehRunLine/archive`（`listVerdictArchive`，可按类别/问话时刻区间/代号）。
- 旧办法 `evaluate/evaluateTop`：名目参数表照旧，输入按界册单位直收（不折算），
  但请界、在位、量程、压线归层已并到同一套口径，返回 1..3，没到起算/越界/无在位界返 0。
- 界册翻检（含停用、已交班，只许看不许拿去比数）：`browseRegister` / `GET /vehRunLine/browse`。
- 界册体检（缺哪格、哪格没越过前一格，指名道姓）：`inspectRegister` / `GET /vehRunLine/inspect`。
- 屏挂悬空代号核对：`danglingScreenCodes` / `GET /vehRunLine/dangling`。

## 2. 严重报警处置单（状态机）

- 表：`t_veh_handle_flow`；服务 `TVehHandleFlowServiceImpl`；入口 `POST /vehHandleFlow/advance|rollback`。
- 环节 `stage` 0定级 1知会 2回告 3归档；情形 `status` 0未起 1在办 2已封尾。
- 步次与情形同源，唯一落法 `moveTo`：一次只挪一档（早先 advance 误 +2 跳档）；
  走到归档(3)即封尾(2)；只要流程动过（含退回头一档重办）都算在办。
- 已封尾：拒推、`updateContent` 拒改、`remove` 拒删（归档在册留查）。
- 头一档退无可退、不存在的单：动作返回 null/false，不抛异常。

## 3. 移交执法核签单（多档签批）

- 表：`t_veh_move_bill`；服务 `TVehMoveBillServiceImpl`。
- 档口 `node_no` 0初核 1复核 2核准；`sign_mode` 0一名即可 1两名同判；
  `need_count` 本档应到人数（空着按 sign_mode 兜底 1/2）；`sign_count` 本档已落印。
- 情形 `status` 0在核 1已办结 2已否决。
- `approve`：**先在本档 sign_count+1，再判够不够应到人数**；够了清零进下一档，过了核准才办结；
  没够档口不动。（早先先 +node 再拿旧值判过档，永远差一档。）
- `reject`：一票否决即终态 status=2（早先只空 update，仍挂"在核"）。
- `rollback`：退上一档、本档落印作废清零、重回在核；初核退无可退返 null。
- 已办结/已否决封档，不再落印。

## 4. 证照到期催办（周期任务）

- 表：`t_veh_due_task`；服务 `TVehDueTaskServiceImpl`（旧驱动）。
- `due_at` 应开口止点；`amount` 最可提前几日；`status` 0搁着(待派) 1递出去了(已派出) 2递不动(分不出)（存量空状态按搁着认）。
- **到没到期只有一把尺 `VehDueRuler`（`service/VehDueRuler.java`）**，旧驱动与新批派一趟同用，别处不许另摆：
  - 开口点 = 止点往回点 `amount` 个**办事日**（不是自然日/24 小时）：一日一日回点，
    不办事的整日跳过不计数，止点带几点几分开口点还是几点几分（夜里跨的几个钟头不凑整日）；
  - 不办事日历读 `t_veh_nonwork_day`（`day_kind` 0国家放假 1省里下文停办），经 `VehDueWorkdayCalendar` 逐日剔；
  - 触发时刻已到开口点（**含开口/止点那一刻，压线算到期**）即开口，逾期照催；
  - 时刻读法两处一致：挑桩与回写同走墙钟字符串 `yyyy-MM-dd HH:mm:ss`（避开 JDBC serverTimezone 错位）。
- 旧驱动：开口判定 `isDispatchable` 一处（已改为委托 `VehDueRuler`），`runOnce` 直接照 `listDue`
  那份名单派（两处同源），逐条置"已派出"，空名单/时刻缺省返 0（早先空名单取 `get(0)` 崩），
  单条失败跳过不牵动别条。**旧驱动照旧只出一个数、不添新东西、模样不改**；
  旧驱动从宽：amount 空着/带负号按 0 兜底（`parseAmountLenient`）。

## 4b. 证照到期批派一趟（新名目，一趟只办一批）

- 新表：`t_veh_nonwork_day`（不办事日历）、`t_veh_due_handler`（经办人册，`status` 0在位 1本季休办、
  `quota` 本季定额）、`t_veh_due_handler_kind`（经办人经得来的业户类别，对 `t_veh_ent.site_type`）；
  `t_veh_due_task` 加格：`site_no`/`contact`/`assignee_no`/`dispatched_at`/`block_stage`/`block_reason`
  （旧库补列见 veh.sql 末尾）。
- 服务 `TVehDueDispatchServiceImpl`，**唯一入口** `POST /vehDueDispatch/dispatch?at=yyyy-MM-dd HH:mm:ss`
  （at 缺省按问话当下；控制器只递话，不算一个数）。"要一趟只办一批"的活只由这一名目担，不回头改旧驱动。
- 开口窗口唯一判定 `VehDueRuler.withinWindow`：**[02:00, 05:00)**——两点以前、五点及以后是静默段，
  按下触发只应声 `windowOpen=false`、三数留空（不是 0），不读册、不动库、行上一格也不改；at 缺省同样不动。
- 挑桩（与旧驱动同一把尺）：只挑搁着的（`上趟已递出去/递不动不捞回`），行格先过堂：
  `due_at` 空、`amount` 没数/带负号/大得没边（顶 `MAX_AHEAD_DAYS=3650`）、`item_no` 没落，
  当场置 2 并把卡环（止点时刻/提前日/条目代号）写回 `block_stage`、缘由写 `block_reason`；
  **一桩撑不住不掀翻这一趟**，别桩照旧一桩一桩走完。
- 两类天生递不动（不必等到期、不必空跑）：所对业户在 `t_veh_ent` 已销户(`status=1)`或查无此代号
  （卡环"业户"）；`contact` 空着/空白（卡环"联络人"）。
- 截断：挑中多过 `BATCH_CAP=500` 时，按 `due_at` 先来先办、同止点按 `item_no` 字典序，
  只办头 500，余下留在搁着等下一趟（`leftToNext` 报数、mark 里摆明，不许一口气捞穿整张表）。
- 递到谁名下（`prepareAssignment`/`Assignment.pick`）：按这一季（季头 `VehDueRuler.quarterStart`）
  手上活**最少**的先接（季内已派出条数，含本趟占位；平手按代号排，确定性）；
  本季休办、册里没挂这类业户（经不来）、名下已压着同一 `site_no`（一家业户的几桩不并同一人，
  跨本趟的季内历史也认）、已塞满定额的，逐位顺延；谁都接不下当场置 2，卡环"派单"、缘由写回行上。
  名额在**落库成功后**才 commit 占位；该条写库抛错则不占名额、行留在搁着，不牵动别桩。
- 办过的两边痕迹同进同退：`status` 翻 1 **且** `dispatched_at`=触发那一刻、`assignee_no`=经办人
  同一回 update；只翻词不钉时刻/经办人的，回库点不算办过。
- 收口三数（`VehDueDispatchReceipt`：还欠开口/这一趟递出去/标成递不动）**只报这一趟这一回，
  不并历史**；`recount` 重新回库整册逐条点（按行 id 对本趟、库里确已落成）才出口，
  不拿挑桩时屏上那一遍凑数。还欠开口 = 收口时仍搁着、行格站得住、尺子说还没到开口点
  （超顶留下的到期桩不算欠开口）。一条没挑着 mark="这一趟没有到期的"，寻常收口不当事故。

## 5. 月度报警处置核收册 / 联网清单页（已收口，改动别动其口径）

- 核收册 `t_veh_alarm_batch` + `t_veh_alarm_row`：整册一道门 `importBatch`；
  行结果只许 未勾/收下/退回；回执三数回库现点现取；撤册后册码才让重递；
  空册不发核讫；`archiveStaleRows` 只把隔两个报送年度仍未勾的行挪往期层，往期照旧翻得着。
- 联网清单页 `t_veh_bind_page`：唯一写入判路 `insertTVehBindPage/updateTVehBindPage/saveFromForm`；
  还差几项一律系统按 应联-已联 轧（不手填）；进展随轧差定 新录/已联齐/缺项；
  开屏页头三栏与名单同一条 SQL 同一回点算（`TVehBindPageBoardMapper.xml`）。

## 6. 跑测试

```
mvn -o test
```

不启 Spring、桩掉 Mapper 的单测在 `src/test/java/com/fc/v2/service/`，
需求点过名的坑（压线、同一把舍法、历史窗口、说话次序、停用、悬空代号、状态同源、签批过档、催办窗口、
批派窗口静默段/办事日剔假/废格卡环/销户空联络人/同户相斥/休办定额顺延/双痕迹/回库现点/五百截断等）
都有对应用例钉着；改口径先改/加这些用例。
