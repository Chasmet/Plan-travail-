# Plan Travail Orsay

Application Android en Java pour suivre les rues d’Orsay, leur avancement dans la semaine et les particularités de terrain.

## Installer et mettre à jour

Télécharger l’APK de la [dernière Release](https://github.com/Chasmet/Plan-travail-/releases/latest), ou utiliser **Réglages → Vérifier la mise à jour**. Installer par-dessus l’application existante pour conserver les données. Android peut demander d’autoriser Plan Travail à installer sa mise à jour.

La clé et le certificat de signature existants sont conservés. Le workflow vérifie la continuité de signature avant publication. L’application contrôle aussi le nom du paquet, la version et le certificat de l’APK téléchargé. **Reprendre la mise à jour** relance une installation annulée ; **Annuler le téléchargement** permet de recommencer.

Android 5.0 minimum (API 21), compilation et cible API 34, Java 17.

## Utiliser le plan

- Rechercher une rue ou la toucher sur la carte, puis choisir 25, 50, 75 ou 100 %. Un nom ambigu ouvre un choix explicite.
- Corriger un pourcentage en sélectionnant la valeur souhaitée. Le bouton **Aujourd’hui** permet d’annuler la dernière modification et de retrouver la valeur précédente.
- Utiliser **Semaine** pour consulter ou exporter une autre semaine. L’historique permet de choisir une date plus ancienne.
- Ouvrir le **Lexique** pour ajouter, rechercher ou modifier une note. Un appui long permet sa suppression.
- Le catalogue des rues et le contour communal sont inclus pour le premier lancement hors ligne. **Actualiser** tente de les renouveler sans supprimer le catalogue local en cas d’erreur. Le fond de carte en tuiles nécessite du réseau ou des tuiles déjà en cache.

Le pourcentage représente une longueur estimée sur l’ensemble des voies qui portent ce nom. Il ne localise pas exactement un tronçon parcouru ni son sens. Les compteurs distinguent les rues commencées et celles à 100 %.

## Sauvegarde et exports

Chaque modification programme une copie locale des rues et du lexique. **Réglages → Exporter rues et lexique** crée un fichier JSON complet à conserver hors du téléphone. La restauration vérifie le format et l’intégrité, puis remplace les données dans une transaction. Une copie préalable permet de revenir avant la dernière restauration.

**Exporter HD PNG + PDF** génère le plan complet de la semaine affichée, indépendamment du filtre et du zoom. L’export utilise les géométries locales et ne télécharge pas une mosaïque de tuiles. Le PDF A4 comprend les dates, les pourcentages et le lexique, avec continuation des notes longues sur les pages suivantes. Les fichiers peuvent être ouverts ou partagés. Sur Android 10 et suivants ils sont également publiés dans Images / Téléchargements, dossier « Plan Travail Orsay ».

## Synchronisation existante

Le service Android conserve son endpoint :

`https://sync30-paddle-api.onrender.com/plan-travail/mcp`

Il utilise les routes `/commands`, `/device-state` et `/command-result`, avec l’identifiant existant `orsay-main`. Les commandes reçues sont enregistrées dans SQLite avant application. Une commande ne réussit qu’après validation des rues et relecture des données. Son résultat est conservé puis renvoyé si sa confirmation échoue. Un identifiant déjà appliqué n’exécute pas la modification une seconde fois. Une erreur de rue dans un lot refuse tout le lot.

Les écrans ouverts se rafraîchissent après une modification. Les réglages affichent la dernière synchronisation, le résultat de la dernière commande et les confirmations encore en attente. L’arrêt de la synchronisation automatique est respecté.

Le serveur MCP embarqué écoute uniquement sur `127.0.0.1:8765`. Le service Render public et son mode d’authentification existants ne sont pas modifiés par cette mise à jour.

**Source du serveur :** le `server.js` historique présent dans ce dépôt ne correspond pas au module Render actuellement utilisé. Il ne doit pas remplacer ce module en production : il ne contient pas tout le contrat Android ci-dessus. La garantie de remise avant réception sur le téléphone dépend encore de la file du serveur Render ; le journal Android protège les commandes déjà reçues.

## Compilation et vérifications

Avec un JDK 17 et le SDK Android 34 :

```sh
./gradlew testDebugUnitTest assembleRelease lintDebug
```

Les tests couvrent la migration de la base v3, les corrections et annulations, les anciennes semaines, le refus des noms ambigus, la reprise des commandes, la restauration atomique, les dates concurrentes, les géométries, le dialogue de marquage sur API 21/28 et le PDF avec un lexique long. Aucun test n’envoie de commande au service Render de production.

Le workflow construit et teste les branches de correction. Seule une exécution sur `main` publie une Release installable dans le circuit interne. Le certificat de signature reste celui des versions précédentes.

## Données cartographiques

Rues : © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), données sous ODbL. Contour communal : [API Découpage administratif](https://geo.api.gouv.fr/decoupage-administratif). Sources et date du catalogue intégré : [NOTICE.md](app/src/main/assets/NOTICE.md).
