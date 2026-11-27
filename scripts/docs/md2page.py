#!/usr/bin/env python3
"""把 docs/reports 下的 markdown 转成站点风格的静态 HTML（移动端优先）。

用法：python3 scripts/docs/md2page.py docs/reports/xxx.md docs/reports/xxx.html "标题" "副标题"
不引入站点框架：docs/ 是 Pages 站点根，只需要一份能直读的静态页。
"""
import sys, pathlib
import markdown

CSS = """
:root{
  --paper:#EDE3DC; --bg:#F8EFE9; --card:#FFFFFF; --sunken:#F4E9E2;
  --ink:#2E2620; --ink2:#6B5B4E; --ink3:#756450;
  --teal:#0E5D6B; --tealWeak:rgba(14,93,107,.08);
  --line:rgba(46,38,32,.09);
}
*{box-sizing:border-box;-webkit-font-smoothing:antialiased}
body{margin:0;padding:34px 20px 90px;background:var(--paper);color:var(--ink);
  font-family:"PingFang SC","Noto Sans SC","Microsoft YaHei",system-ui,sans-serif;
  font-size:15px;line-height:1.78}
.wrap{max-width:680px;margin:0 auto}
.badge{display:inline-block;padding:5px 12px;border-radius:999px;background:var(--tealWeak);
  color:var(--teal);font-size:12px;font-weight:600;letter-spacing:.03em}
h1{font-size:25px;line-height:1.32;font-weight:600;margin:14px 0 8px;letter-spacing:-.01em}
.sub{margin:0 0 26px;color:var(--ink2);font-size:13.5px}
h2{font-size:18px;font-weight:600;margin:36px 0 12px;padding-top:16px;border-top:1px solid var(--line)}
h3{font-size:15.5px;font-weight:600;margin:24px 0 8px;color:var(--teal)}
p{margin:10px 0}
ul,ol{margin:10px 0;padding-left:22px}
li{margin:5px 0}
strong{font-weight:600}
code{background:var(--sunken);border-radius:5px;padding:1px 5px;font-size:12.8px;
  font-family:ui-monospace,SFMono-Regular,Menlo,monospace;word-break:break-word}
pre{background:#2E2620;color:#F0E7E0;border-radius:12px;padding:14px 16px;overflow-x:auto;
  font-size:12.2px;line-height:1.65}
pre code{background:none;padding:0;color:inherit;font-size:12.2px}
blockquote{margin:14px 0;padding:10px 14px;border-left:3px solid var(--teal);
  background:var(--tealWeak);border-radius:0 10px 10px 0;color:var(--ink2);font-size:14px}
blockquote p{margin:2px 0}
table{width:100%;border-collapse:collapse;margin:14px 0;font-size:13px;
  background:var(--card);border-radius:12px;overflow:hidden}
th,td{padding:9px 11px;text-align:left;vertical-align:top;border-bottom:1px solid var(--line)}
th{background:var(--sunken);font-weight:600;font-size:12.5px;color:var(--ink2)}
tr:last-child td{border-bottom:none}
a{color:var(--teal);text-decoration:none;border-bottom:1px solid rgba(14,93,107,.3)}
hr{border:none;border-top:1px solid var(--line);margin:30px 0}
.tblwrap{overflow-x:auto;-webkit-overflow-scrolling:touch}
.foot{margin-top:44px;padding-top:16px;border-top:1px solid var(--line);
  color:var(--ink3);font-size:12.5px}
@media (max-width:520px){
  body{font-size:14.5px;padding:26px 15px 70px}
  h1{font-size:22px}
  /* 手机上没有横向滚动条，表格挤成两列只会把右半边截掉：
     改成「每条记录一块」，首列当小标题、其余按行铺开。 */
  table,tbody,tr,td{display:block;width:100%}
  thead{display:none}
  table{background:none}
  tr{background:var(--card);border-radius:12px;padding:8px 0 6px;margin:0 0 9px;
    border:1px solid var(--line)}
  td{border-bottom:none;padding:4px 13px;font-size:13.2px}
  td:first-child{font-weight:600;color:var(--teal);font-size:12.5px;letter-spacing:.01em}
  .tblwrap{overflow:visible}
}
"""

HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<style>{css}</style>
</head>
<body>
<div class="wrap">
<span class="badge">{badge}</span>
<h1>{title}</h1>
<p class="sub">{sub}</p>
{body}
<p class="foot">北辰 · Polaris · 这份文件由仓库内 markdown 生成，源文件：<code>{src}</code></p>
</div>
</body>
</html>
"""


def drop_lead_h1(html: str) -> str:
    """正文首个 <h1> 与模板标题重复，去掉它（标题只出现一次）。"""
    start = html.find("<h1>")
    if start == -1:
        return html
    end = html.find("</h1>", start)
    return html[:start] + html[end + 5:] if end != -1 else html


def wrap_tables(html: str) -> str:
    return html.replace("<table>", '<div class="tblwrap"><table>').replace("</table>", "</table></div>")


def main() -> None:
    # badge 可选：同一套样式已经不只用于「修复纪实」，写死会让总报告挂着错标签。
    src, dst, title, sub, *rest = sys.argv[1:6]
    badge = rest[0] if rest else "修复纪实"
    text = pathlib.Path(src).read_text(encoding="utf-8")
    md = markdown.Markdown(extensions=["tables", "fenced_code", "sane_lists", "nl2br"])
    body = wrap_tables(drop_lead_h1(md.convert(text)))
    out = HTML.format(
        title=title, sub=sub, body=body, css=CSS, badge=badge,
        src=pathlib.Path(src).name,
    )
    pathlib.Path(dst).write_text(out, encoding="utf-8")
    print(f"wrote {dst} ({len(out)} bytes)")


if __name__ == "__main__":
    main()
