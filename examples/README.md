# 标准扩展验收包

Page Marker 是普通 Manifest V3 扩展，不使用 ChromeXt 专用 API。

在 dev 容器中运行：

~~~sh
python3 tools/package-example.py
~~~

输出 build/examples/page-marker.zip。M1 仅验证导入、权限展示、重启后列表保留及卸载；当前开发版本不会执行它。

后续 M2 的行为验收：在支持扩展的浏览器加载 page-marker 目录，打开 example.com，在扩展 popup 中点击按钮，应在网页插入标记并把标题回传 popup；在 options 修改标记文字，重启后应保留。M2 在手机上实现后使用同一套未经改写的文件验收。
