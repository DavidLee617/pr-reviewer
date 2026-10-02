可读性、健壮性与可维护性问题。

- 空的 catch 块，或 catch 后只打印 / 吞掉异常、不处理也不重新抛出
- 捕获过宽的异常（catch Exception / Throwable）并掩盖了真实错误
- 未释放资源：流、连接、Reader/Writer 等没有使用 try-with-resources
- Optional 直接调用 get() 而未判断；可能为 null 的返回值未检查就使用
- 方法过长（超过约 50 行）或嵌套过深（超过 3 层），应拆分
- 重复代码：同一段逻辑在多处复制粘贴，应提取方法
- 魔法数字 / 魔法字符串：业务含义不明的字面量应提取为常量或枚举
- 使用 System.out / System.err / printStackTrace 代替日志框架
- 修改数据的多步操作缺少 @Transactional，或在同一类内部调用导致 @Transactional 不生效
- 循环中逐条查询数据库（N+1 查询），应批量查询
- 金额使用 double / float 计算，应使用 BigDecimal
- 注释与代码不一致，或留下被注释掉的代码、TODO 未处理
