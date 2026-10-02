安全问题。

- SQL / JPQL 注入：用字符串拼接构造 @Query、EntityManager.createQuery / createNativeQuery、JdbcTemplate 的 SQL，应使用参数绑定
- 硬编码密钥：密码、token、API key 等凭据写死在代码里，应从配置或密钥管理读取
- 越权访问（IDOR）：按 id 查询 / 修改 / 删除资源时，未校验资源是否属于当前用户
- 缺少认证或授权：敏感操作没有权限校验，或身份判断依赖客户端可伪造的信息
- 未校验输入：@RequestBody 缺少 @Valid；数值参数未校验范围（负数、零、过大）
- 业务状态校验缺失：修改资源状态前未校验当前状态是否允许该操作
- 并发问题：先查后改的共享数据更新没有加锁或原子更新，存在竞态
- 敏感信息泄露：日志或响应中输出密码、token、证件号等；把异常堆栈或 ex.getMessage() 原样返回给客户端
- 不安全的反序列化：ObjectInputStream 读取外部数据；Jackson 开启 default typing
- 路径穿越：用外部输入拼接文件路径
- 弱随机数：用 java.util.Random / Math.random 生成 token 等安全相关值，应使用 SecureRandom
- 直接把实体类（@Entity）作为请求体绑定，导致客户端可修改不该修改的字段（批量赋值）
