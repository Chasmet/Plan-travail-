#!/usr/bin/env python3
"""Build the attributed offline Orsay vector dataset from OpenStreetMap (ODbL)."""
import gzip
import json
from pathlib import Path
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
QUERY = '''[out:json][timeout:90];area["ref:INSEE"="91471"]["boundary"="administrative"]->.a;
(way["highway"](area.a);way["building"](area.a);way["landuse"](area.a);
way["natural"](area.a);way["leisure"](area.a);way["waterway"](area.a);
way["railway"="rail"](area.a););out geom;'''

def main():
    body = urllib.parse.urlencode({'data': QUERY}).encode()
    request = urllib.request.Request('https://overpass-api.de/api/interpreter', data=body,
        headers={'User-Agent': 'PlanTravailOrsay/1.1 (offline municipal map build)'})
    with urllib.request.urlopen(request, timeout=115) as response:
        raw = json.load(response)
    features = []
    for way in raw.get('elements', []):
        tags, geom = way.get('tags', {}), way.get('geometry', [])
        if len(geom) < 2:
            continue
        points = [[round(p['lat'], 7), round(p['lon'], 7)] for p in geom]
        kind = None
        if 'building' in tags:
            kind = 'building'
        elif 'highway' in tags:
            kind = 'road'
        elif tags.get('railway') == 'rail':
            kind = 'rail'
        elif tags.get('natural') == 'water' or tags.get('landuse') in ('reservoir', 'basin'):
            kind = 'water'
        elif tags.get('waterway'):
            kind = 'waterline'
        elif tags.get('natural') in ('wood', 'scrub') or tags.get('landuse') == 'forest':
            kind = 'wood'
        elif tags.get('leisure') in ('park', 'garden', 'pitch', 'playground', 'sports_centre') or tags.get('landuse') in ('grass', 'meadow', 'recreation_ground', 'allotments') or tags.get('natural') in ('grassland', 'heath'):
            kind = 'green'
        elif tags.get('landuse') in ('industrial', 'commercial', 'retail', 'cemetery', 'residential'):
            kind = 'land'
        if kind:
            features.append({'id': way['id'], 'kind': kind, 'type': tags.get('highway', tags.get('landuse', '')),
                'name': tags.get('name', ''), 'points': points})
    if len(features) < 1500 or sum(f['kind'] == 'building' for f in features) < 500:
        raise ValueError('Incomplete detailed map: refusing to replace the bundled dataset')
    output = {'version': 1, 'source': 'OpenStreetMap contributors', 'license': 'ODbL-1.0',
        'timestamp': raw.get('osm3s', {}).get('timestamp_osm_base'), 'query': QUERY, 'features': features}
    target = ROOT / 'app/src/main/assets/orsay_vector.dat'
    with gzip.GzipFile(filename=str(target), mode='wb', mtime=0) as gz:
        gz.write(json.dumps(output, ensure_ascii=False, separators=(',', ':')).encode())
    print(json.dumps({'features': len(features), 'bytes': target.stat().st_size,
        'counts': {k: sum(f['kind'] == k for f in features) for k in sorted(set(f['kind'] for f in features))}}))

if __name__ == '__main__':
    main()
