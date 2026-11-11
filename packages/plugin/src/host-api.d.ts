// 宿主（DSH 0.2.x）只为第一方 bundle 提供 `@deepseek-ai/*` 的模块解析：第三方插件
// 通过 profile 安装后，Node 的解析路径里没有宿主的 runtime 目录，因此插件自带的
// 运行时依赖必须全部打进 dist。schemastery 已在 devDependency/根依赖中安装并被
// esbuild 内联（其 Schema 用 Symbol.for('schemastery') 标识，与宿主实例跨副本兼容）。
