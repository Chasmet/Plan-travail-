# Plan et zoom — septembre 2026

La carte doit rester lisible et facile à manipuler sur téléphone, y compris pour dessiner une petite section.

## Changements

- Zoom jusqu’au niveau 24. Le fond classique reste disponible ; au-delà de 19, la géométrie vectorielle intégrée remplace les pixels agrandis. Le mode détaillé peut également être choisi à tous les niveaux, hors connexion.
- Appui court sur +/− : demi-niveau. Appui prolongé : progression par pas de 0,2. Le bouton du niveau ouvre un curseur précis, par pas de 0,05. Les limites sont respectées pour chaque commande.
- Échelle en mètres, retour à la vue d’ensemble, plein écran et reprise du dernier cadrage. Les noms des rues sont placés sur la portion visible, même lorsque ses extrémités sont hors champ.
- En-tête compact, recherche avec suggestions, fiche de rue et boutons tactiles d’au moins 48 dp. Les outils secondaires sont regroupés dans le menu.
- Dessin en plusieurs traits : un doigt dessine, deux doigts déplacent et zooment. Le mode Déplacer permet aussi le déplacement à un doigt. Le brouillon suit une rotation de l’écran.
- Dessins conservés dans la base et les sauvegardes, avec migration transactionnelle des anciens dessins et annulation de leur ajout/suppression. Une ancienne sauvegarde qui ne contient pas de dessins conserve ceux déjà présents.
- Le PDF conserve son fond historique, ses pages de détail et son lexique. Le plan conserve ses proportions sur la page.

## Limites cartographiques

Le zoom augmente la précision d’affichage, pas celle des données OpenStreetMap. Le fond détaillé est un instantané intégré ; il ne contient pas tous les symboles ou adresses du fond classique. La date, la licence ODbL et la méthode d’extraction figurent dans `app/src/main/assets/NOTICE.md`.

## Vérification

`./gradlew testDebugUnitTest lintDebug assembleDebug --max-workers=2`

Les tests comprennent les migrations et restaurations, l’annulation, les limites et pas de zoom, la conservation du centre, l’arrêt d’un appui prolongé, le fond réellement chargé depuis les assets de l’APK, le dessin/pincement et le changement de semaine. Des captures sont produites dans `app/build/verification`.

Le circuit GitHub de publication et le certificat existant sont conservés. Les mises à jour s’installent sur la version précédente.
