#!/usr/bin/env bash
# 把战役结构从 maps/map_001.json 迁到 map/<战役名>.json
#
# 这不是一次性的临时脚本：把它留在 tools/ 里，是因为"战役结构变了、
# 玩家的旧战役要跟上"这件事将来还会发生，而下次改结构时
# 照着这份改比从零写安全。
set -euo pipefail

cd "$(dirname "$0")/.."

python - <<'PY'
import io, json, os, shutil, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

def migrate_campaign(dirpath, *, expect_name=None):
    """迁移一个战役目录。返回 (改动描述列表, 是否有改动)。"""
    changes = []
    cj = os.path.join(dirpath, 'campaign.json')
    if not os.path.isfile(cj):
        return changes, False

    with io.open(cj, encoding='utf-8') as f:
        meta = json.load(f)

    name = meta.get('name') or os.path.basename(dirpath)
    if expect_name:
        name = expect_name
    old_maps = os.path.join(dirpath, 'maps')
    new_map_dir = os.path.join(dirpath, 'map')

    # 找出旧的唯一地图文件
    old_map = None
    if os.path.isdir(old_maps):
        entries = sorted(x for x in os.listdir(old_maps)
                         if x.lower().endswith('.json'))
        if len(entries) == 1:
            old_map = os.path.join(old_maps, entries[0])
        elif len(entries) > 1:
            print('  !! %s 的 maps/ 里有 %d 个文件，不敢自动挑，跳过'
                  % (dirpath, len(entries)))
            return changes, False

    if old_map is None and not os.path.isdir(new_map_dir):
        print('  -- %s 找不到地图，跳过' % dirpath)
        return changes, False

    # 目标：map/<id>.json —— id 就是目录名
    cid = os.path.basename(dirpath)
    os.makedirs(new_map_dir, exist_ok=True)
    new_map = os.path.join(new_map_dir, cid + '.json')

    if old_map and os.path.abspath(old_map) != os.path.abspath(new_map):
        shutil.move(old_map, new_map)
        changes.append('地图 %s -> map/%s.json'
                       % (os.path.relpath(old_map, dirpath), cid))

    # 更新 campaign.json 的 map 与 id。
    #
    # ⚠️ map 绑的是 **id（= 目录名）**，不是显示名 —— 显示名可以含全角冒号
    # 这类不能进文件名的字符（官方战役的「第一关：边境哨站」就是），
    # id 则必须同时是合法目录名。所以三者里只有显示名允许跟另外两个不同。
    #
    # 这里曾经错写成用 name，把 official_01 的 id 改成了「第一关：边境哨站」，
    # 于是文件说的和目录名对不上 —— 靠 tools/check-campaign-layout.py 才发现。
    want_map = 'map/%s.json' % cid
    if meta.get('map') != want_map or meta.get('id') != cid:
        meta['map'] = want_map
        meta['id'] = cid
        meta.setdefault('name', cid)
        with io.open(cj, 'w', encoding='utf-8', newline='') as f:
            json.dump(meta, f, ensure_ascii=False, indent=2)
            f.write('\n')
        changes.append('campaign.json 的 map/id -> %s' % cid)

    # 清掉空的 maps/
    if os.path.isdir(old_maps):
        left = os.listdir(old_maps)
        if not left:
            os.rmdir(old_maps)
            changes.append('删掉空的 maps/')
        else:
            print('  !! %s 的 maps/ 还有 %s，未删' % (dirpath, left))

    # 地图文件内部的 name 也要对齐到 id（否则打开后标题栏显示的是别的名字）
    if os.path.isfile(new_map):
        with io.open(new_map, encoding='utf-8') as f:
            m = json.load(f)
        if m.get('name') != cid:
            m['name'] = cid
            with io.open(new_map, 'w', encoding='utf-8', newline='') as f:
                json.dump(m, f, ensure_ascii=False, indent=2)
                f.write('\n')
            changes.append('地图内部 name -> %s' % cid)

    return changes, bool(changes)


def walk(root):
    if not os.path.isdir(root):
        print('（没有 %s）' % root)
        return
    print('=== %s ===' % root)
    for entry in sorted(os.listdir(root)):
        p = os.path.join(root, entry)
        if not os.path.isdir(p):
            continue
        changes, changed = migrate_campaign(p)
        if not changed:
            print('  = %s 无需改动' % entry)
            continue
        print('  * %s' % entry)
        for c in changes:
            print('      %s' % c)


walk('data/campaigns')
print()
walk('src/main/resources/campaigns')
PY
