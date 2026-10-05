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

### 4.1 旧单轮催办（照旧，别改模样）

- 表：`t_veh_due_task`；服务 `TVehDueTaskServiceImpl`。
- `due_at` 应开口止点；`amount` 最可提前几日；`status` 0待派 1已派出 2分不出（存量空状态按待派认）。
- 开口窗口唯一判定 `isDispatchable`：到 `[due_at - amount 日, …]` 即可派，含开口那一刻，逾期照催；
  只派未留痕删除、待派的；amount 为负按 0 兜底。
- `runOnce` 直接照 `listDue` 那份名单派（两处同源），逐条置"已派出"，
  空名单/时刻缺省返 0（早先空名单取 `get(0)` 崩），单条失败跳过不牵动别条。
- 时间比较统一走墙钟字符串 `yyyy-MM-dd HH:mm:ss`（避开 JDBC serverTimezone 错位）。
- **这一套照旧只出一个数（成功条数），不添新东西；要"一趟只办一批"的活走 4.2 的新名目，别回头改它。**

### 4.2 派批次（新名目，一批一办）

- 服务 `TVehDueDispatchServiceImpl`（接口 `ITVehDueDispatchService`）：
  `preview(at)` 屏上问一声只看不动库、`runBatch(at)` 真走一趟；两道门同一个 `build` 算路，
  回给屏上的就是真走那一趟会算出来的——四问四答（挑中哪几桩/窗口两头动不动手/递到谁名下/
  分不出怎么标）与到没到期那把尺只此一处，控制器（`VehDueDispatchController`）、
  调度（`vehDueDispatchTask.runBatch()`，Quartz 配这个 bean 名）只递话。
- 开口窗口：墙钟 `[02:00,05:00)`，两点起含、五点止不含；窗口外（含 `at` 缺省）只应声
  "这一趟不动库"，一行一格都不许改。
- 到没到期的尺（`isOpen` 一处）：开口点 = `due_at` 往前点满行上 `amount` 个**办事日**；
  办事日按日点，夜里跨过去的钟点不凑整日；在册的不办日只翻 `t_veh_due_offday`
  （0国家放假/1省里停办，周末不在册照算，别处不另定）；含开口那一刻，止点≤触发即办；
  存量行与新行同尺，只认行上那一格的数。挑桩与回写 `dispatched_at` 同一把读法（同一个 at）。
- 只派待派（含存量空状态、未删除）：上趟已派出的不许当搁着的捞回，已分不出的不自动重排。
- 校格撑不住的当场记"分不出（环：校格）"，缘由点名哪一格：代号没落、`due_at` 空、
  `amount` 没数/带负号/大得没边（顶 3650）。一桩撑不住不掀翻整趟，别桩照旧走完。
- 两类天生分不出，不空跑派人：所对业户 `site_no` 在 `t_veh_ent` 已销户或查无（环：销户）；
  联络人 `contact` 空着（环：无联络人）。
- 派人（`t_veh_due_handler` 当季快照）：`active_count` 少者先接、同载按 handler_no；
  这季休办（leave_flag=1）、`accept_types` 里没有该业户 site_type 的、已接过同一 site_no
  另一桩的那一位，往后顺延；轮到的那位 `quota` 已塞满，本桩当场"分不出（环：派人）"，
  **不顺延**；花名册上没人可接同样"分不出（环：派人）"。缘由一并写 fail_reason。
- 五百上限：挑中的多过 500，按 `due_at` 升序、同止点按 `item_no` 升序只办头 500，
  余下留在待派（回执 leftovers 摆明，message 也说清），留到来年那一趟。
- 办成的两边痕迹同一回落齐：status 翻 1 + `handler_no` + `dispatched_at`（=触发 at）；
  回写这一环点不动的补挂"分不出（环：回写）"，补挂也不成则留账上，绝不算成已派出。
- 收口三数只报这一趟这一回（不并历史）：**回库逐条点过才出口**——
  还欠开口（仍待派且尺下未开口，含五百余下）、这一趟已派出、这一趟分不出；
  一条没挑中是寻常收口（"这一趟没有到期的"），不当事故。
- 新列/新表见 `doc/schema/veh.sql`（含旧库 ALTER 语句）：
  `t_veh_due_task` 加 `site_no/contact/handler_no/dispatched_at/fail_stage/fail_reason`；
  新表 `t_veh_due_handler`、`t_veh_due_offday`。
- 与旧 4.1 两处口径一致的边界：同一把墙钟读法、同认待派（含存量空状态）/未删除、
  同含开口那一刻、逾期照催、同只认行上 amount；差别只在数日子——4.2 按办事日点
  （假与停办不凑），4.1 旧轮按自然日且为负兜底 0，属不许动模样的旧账。
  **"先挑哪几桩"只由 4.2 的 `build` 一处定**（preview/runBatch 同源），
  屏上与真跑、挑桩与回写时刻，谁都不许另摆一把尺。

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
需求点过名的坑（压线、同一把舍法、历史窗口、说话次序、停用、悬空代号、状态同源、签批过档、催办窗口等）
都有对应用例钉着；改口径先改/加这些用例。
