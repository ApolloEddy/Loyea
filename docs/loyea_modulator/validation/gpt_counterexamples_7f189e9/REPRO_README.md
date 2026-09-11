# 反例复现包

对应 ApolloEddy/Loyea 的固定提交 7f189e9166cbbe6f9177b19433e5c0818b7dee59。
需要 Python 3.10+、Node.js，以及该提交的完整仓库。无需额外 Python/Node 依赖。

```bash
node reproduce_web.cjs /path/to/Loyea > web_results.json
python3 reproduce_theory.py /path/to/Loyea web_results.json > theory_results.json
```

断言验证“当前缺陷可复现”，成功退出不代表产品通过验收。修正实现后部分断言预期会失败，需要改写为新的正确性断言。
理论脚本执行仓库里的 Python 参考实现；Kotlin 对应逻辑已静态核对，但本次没有编译运行 Kotlin。
网页脚本执行实际内联 JS，采用简化 DOM 与受控 fetch 返回，不包含实际 Kotlin HTTP 服务。
