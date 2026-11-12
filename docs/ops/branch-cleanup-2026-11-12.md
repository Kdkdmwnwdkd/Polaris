# 分支清理记录 2026-11-12

## 结果

远程分支 **17 → 1**（只剩 `main`）。

## 为什么会有 17 个

一个审计项开一个分支（`security/read-boundary`、`fix/r10-async-settle`…），合进 `main` 后
分支没删。`main` 上的提交信息与分支标题一一对应，说明内容都合过；残留的分支只剩
「看起来像 17 件没做完的事」这个误导作用。

判定过程踩了一次坑，记在这里免得下次重犯：

- `git cherry` 报「已包含」只对 patch-id 有效。合并时做过 squash/rebase 的分支，
  patch-id 会变，于是被误报成「有独有补丁」——`fix/approval-host-and-keys` 和
  `security/notification-scope` 就各报了 2 个，实际文件内容与 `main` 逐行一致。
- `git diff main branch` 的 `+` 行数也不能用：分支落后 30 多个提交时，
  `+` 里绝大多数是「分支侧的旧版本行」，不是独有功能。

## 采用的三条判据

| 判据 | 命令 | 含义 |
|---|---|---|
| 合并是否改变内容 | `git merge-tree --write-tree origin/main origin/<b>` | 返回的 tree 等于 `main` 的 tree → 合并后字节级无变化 |
| 源码改动是否已在 main | 分支新增的 `fun`/`class`/`val`/`const` 符号逐个在 `main` 同文件中查找 | 全部命中 → 功能已落地（写法可能被重构过） |
| 反向打补丁 | `git apply --reverse --check` | 能反向应用 → `main` 已包含该改动 |

三条判据的结论一致：**没有一个分支含有 `main` 之外的内容，因此不需要合并任何东西。**
反向打补丁在 11 个分支上报失败，全部落在 `scripts/qa/harness-baseline.txt`、
`docs/使用说明.md`、`run-*.sh` 头部注释这类登记表上——上下文行被后续提交改过，
git 无法定位，与功能无关。

## 删除的分支与 SHA

恢复命令：`git push origin <sha>:refs/heads/<分支名>`
（前提：对象未被 GitHub GC，通常保留 90 天以上）

```
docs/p5-event-fields                     5a78798dc55208bd7d88beb64abc03717772cd74
fix/approval-host-and-keys               d2a77d2998fdfd5fd9bd711fbf9d8b6e6aecdc83
fix/notification-intake                  03592a8d9f571e18f3a0d1e49868736bc59cb1c4
fix/r10-async-settle                     89d699d8f8eff306240df4a67628d029d2ff385e
fix/r13-coldstart-flaky                  47bd69b290ac9754225a2dd5eabee210b9e784a1
security/approval-bidirectional          44ba8a54a193eff992faef5cc13d4c731bd0f229
security/log-redaction                   241931fe30694314f7e612c29711b9a7450203c3
security/mcp-log-redaction               7ecd16ad85dc4949c4c405e327590c8421effc62
security/notification-body-leak          e8d0754233bbdec778a467e9cf43b0452072171d
security/notification-scope              3b6e6f1255d95e53452cfd0fc73e906ecb744251
security/ocr-screen-approval             bbd3c0414487e51f9212df26839b67513ee69979
security/private-read-approval           dfd6de936d8d3b6d93ff40aba645d727dae8bb13
security/read-boundary                   76292701720daccd0852c083599d334ce7061866
security/skill-declaration               6d050c486cbb1cc65145532268c9ff71db99e3fe
security/skill-net-egress                48a3c9ddd34fd4f65e9cc9547e725cd914656c37
security/skill-package-limit             c33917076c00b01c9a8cd8d559f16d3a6152d700
```

## 以后的规矩

分支合进 `main` 就在 PR 里勾「Delete branch」，或者合并后立刻
`git push origin --delete <分支>`。留在仓库里的分支不占空间，但会让人反复确认
「这 16 个是不是没做完」——成本花在重复判断上，不是磁盘上。
