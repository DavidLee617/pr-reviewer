Java 命名约定与命名质量。

- 类、接口、枚举、record：PascalCase（BookService）；接口不加 I 前缀
- 方法、变量、参数：camelCase（getById、userId）
- 常量（static final 基本类型 / 字符串 / 不可变对象）：UPPER_SNAKE_CASE（MAX_PAGE_SIZE）
- 枚举常量：UPPER_SNAKE_CASE
- 包名：全小写，不含下划线
- 布尔变量 / 方法：使用 is / has / can 等前缀，含义为真时可读通（isPaid，而不是 paidFlag）
- 含义不清的名字：单字母（循环变量除外）、temp、data、info、obj、list1 等
- 误导性命名：名字与行为不符（如 getXxx 带副作用、checkXxx 实际执行修改、名字是单数实际是集合）
- 缩写不一致或难以理解（如 usrCnt），同一概念在不同地方用不同名字
