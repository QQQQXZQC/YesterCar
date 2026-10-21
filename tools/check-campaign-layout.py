#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""检查战役目录是否满足契约。

契约（见 CampaignMeta 与具体设计 §4.1）：
  * id  ≡ 目录名            —— CampaignJsonIO.parse 一律以目录名为准，文件写别的就是不一致
  * map ≡ map/<id>.json     —— 地图文件名绑 **id**，不是显示名
  * map 指向的文件必须存在

**为什么地图文件名绑 id 而不是显示名**：
显示名是给玩家看的，可以含全角冒号这类**不能进文件名**的字符
（官方战役的「第一关：边境哨站」就是），而 id 必须同时是合法目录名。
所以"显示名"是三者里唯一可以跟另外两个不一样的那个。
"""
import io, json, os, sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')


def check(root):
    if not os.path.isdir(root):
        return 0, 0
    print('=== %s ===' % root)
    bad = total = 0
    for entry in sorted(os.listdir(root)):
        d = os.path.join(root, entry)
        cj = os.path.join(d, 'campaign.json')
        if not os.path.isdir(d) or not os.path.isfile(cj):
            continue
        total += 1
        meta = json.load(io.open(cj, encoding='utf-8'))
        cid, name, mpath = meta.get('id'), meta.get('name'), meta.get('map')
        problems = []

        if cid != entry:
            problems.append('id=%r 应等于目录名 %r' % (cid, entry))

        want = 'map/%s.json' % entry
        if mpath != want:
            problems.append('map=%r 应是 %r' % (mpath, want))

        if mpath and not os.path.isfile(os.path.join(d, mpath)):
            problems.append('map 指向的文件不存在')

        if not name:
            problems.append('name 为空，主菜单会没法显示')

        # 地图文件内部的 name 应当与 id 一致（否则打开后标题栏显示别的名字）
        if mpath and os.path.isfile(os.path.join(d, mpath)):
            m = json.load(io.open(os.path.join(d, mpath), encoding='utf-8'))
            if m.get('name') != entry:
                problems.append('地图内部 name=%r 应等于 %r' % (m.get('name'), entry))

        if problems:
            bad += 1
            print('  !! %s' % entry)
            for p in problems:
                print('       %s' % p)
        else:
            extra = '' if name == entry else '   显示名=%s（与 id 不同，正常）' % name
            print('  OK %s   map=%s%s' % (entry, mpath, extra))

    print('  -> %d 个战役，%d 个有问题' % (total, bad))
    return total, bad


t = b = 0
for root in ('data/campaigns', 'src/main/resources/campaigns'):
    a, c = check(root)
    t += a
    b += c
    print()
print('合计 %d 个战役，%d 个有问题' % (t, b))
sys.exit(1 if b else 0)
