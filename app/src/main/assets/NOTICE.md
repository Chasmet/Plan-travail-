# Catalogue d’Orsay intégré

Instantané récupéré le 10 septembre 2026 pour le démarrage hors ligne.

- `orsay_streets.json` : données © OpenStreetMap contributors, sous Open Database License (ODbL). https://www.openstreetmap.org/copyright
- Extraction Overpass : zone administrative `ref:INSEE=91471`, voies ayant les attributs `highway` et `name`, géométries complètes. 955 voies ; plusieurs voies peuvent porter le même nom.
- `orsay_boundary.geojson` : contour de la commune 91471, API Découpage administratif, https://geo.api.gouv.fr/communes/91471?geometry=contour&format=geojson

Les fichiers restent disponibles localement quand le réseau est absent. Le bouton d’actualisation renouvelle le cache privé après validation. L’export cite les sources.

## Plan vectoriel détaillé

`orsay_vector.dat` contient du JSON compressé en gzip : instantané OpenStreetMap du 15 septembre 2026, extrait via Overpass par `scripts/prepare_vector_map.py`. Bâtiments, voies, eau et espaces verts d’Orsay, sous ODbL (© OpenStreetMap contributors). Ce fond est redessiné à la résolution de l’écran jusqu’au zoom 24. L’agrandissement ne crée pas de données géographiques supplémentaires.

L’extension `.dat` conserve le gzip intact dans l’APK ; Android Gradle décompresse automatiquement les fichiers `.gz` lors de la fusion des assets. Le bouton d’actualisation concerne le catalogue des rues et le contour, pas cet instantané.
