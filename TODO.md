# AnimaFabric TODO

## P1 — 方块与工具
- [ ] 方块分类系统（canWalkOn / canWalkThrough / avoidWalkingInto / isReplaceable）
- [ ] 工具感知挖掘代价（ToolSet + 附魔效果）

## P2 — 寻路算法增强
- [ ] 超时 + 多系数 A*（替代迭代限制，支持返回次优路径）
- [ ] 回避系统（backtrack avoidance + mob avoidance）
- [ ] 区块边界感知（避免寻路到未加载区块）
- [ ] Long2ObjectOpenHashMap 优化节点存储

## P2 — 架构改进
- [ ] Process/Behavior 分离架构（将目标行为与 tick 驱动行为解耦）
- [ ] 线程安全 CalculationContext 快照

## P3 — 任务与反馈
- [ ] 声明式任务模板（Malmo 风格 JSON Mission Schema）
- [ ] 可组合奖励系统
- [ ] 会话录制回放

## P3 — 高级功能
- [ ] 攀爬移动（梯子/藤蔓）
- [ ] 搭桥放置（侧放/后放优化）
- [ ] 路径拼接（Path Splicing）
- [ ] 移动中断安全检查
