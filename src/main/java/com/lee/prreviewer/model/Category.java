package com.lee.prreviewer.model;

/**
 * 审查类别，对应规则文件 rules/style.md、security.md、naming.md、logic.md、perf.md。
 * LOGIC / PERF 在 v1.4 加入：此前逻辑 bug 只能归到 STYLE，类别一致率没有意义。
 */
public enum Category { STYLE, SECURITY, NAMING, LOGIC, PERF }
