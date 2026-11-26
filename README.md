<div align="center">
<img src="docs/icon.png" width="112" alt="Kite Driving logo">

# Kite Driving

An experimental Android fork of Kite Maps for driving, walking, cycling, public transport and exploring places.

[Download the Android APK](https://github.com/Gordicek/kite-maps/releases) · [What's changed](docs/ANDROID-CHANGES.md) · [Report a bug](https://github.com/Gordicek/kite-maps/issues) · [Credits and data sources](#credits-and-data-sources)
</div>

## About me

I'm **Gordicek**, and I don't have a programming background. I used **ChatGPT 6.2 Sol** to make the changes for this fork. I wanted one map app that could handle my everyday drives, a public transport connection, a walk through a new city and the occasional hike without making me switch apps.

This is a hobby project built on [MatteoFX1's Kite Maps](https://github.com/MatteoFX1/kite-maps), which itself builds on [PimpinPumpkin's Vela Maps](https://github.com/PimpinPumpkin/Vela). There are still plenty of bugs. I especially appreciate reports that include the Android version, route mode, routing engine and what happened. **Settings → About → Share diagnostics** creates a report that you can review before sharing.

> Jsem Gordicek, programování nerozumím a ke změnám jsem použil ChatGPT 6.2 Sol. Chtěl jsem jednu mapovou aplikaci na auto, chůzi, MHD, kolo i výlety. Je to můj hobby fork Kite Maps a pořád má dost chyb.

## Install on Android

1. Download the ARM64 APK from the [GitHub releases](https://github.com/Gordicek/kite-maps/releases). The app installs as **Kite Driving** with package ID `app.kite.driving`.
2. On first launch, connect Mapbox with a **public** access token beginning with `pk.`. You can get one from your [Mapbox account](https://account.mapbox.com/access-tokens/). This enables the recommended Mapbox Standard map and traffic-aware car routing. OpenFreeMap is available without a token.
3. Choose whether to use optional Google data and Waze reports. You can change these choices later. Mapbox is under **Settings → Map & layers → Map provider**. All three routing-engine settings are under **Settings → Route engines**.

Updates of this fork signed with the same certificate install over earlier Kite Driving builds. See [the full Android changelog](docs/ANDROID-CHANGES.md) for what differs from the original Kite. For the original releases and the iPhone app, see [upstream Kite Maps](https://github.com/MatteoFX1/kite-maps).

## Features at a glance

| Map and places | Routes and navigation |
| --- | --- |
| Mapbox Standard, OpenFreeMap, satellite imagery and downloadable map areas | Driving, cycling, walking and public transport |
| 3D terrain, buildings, trees and street lamps, hiking and cycling layers | Route-engine choice, alternative route times, reorderable stops and map-based picking |
| Day and night palettes, sunrise and sunset, Material 3 wallpaper colors and three icon packs | Lane guidance, road alerts, traffic on the route and nearby parking on arrival |
| Google and OpenStreetMap places, indoor floors, interactive Street View and saved lists | Transit departures and transfers, route weather, elevation profiles and surface information |
| Czech interface, voice guidance and dictation | Picture-in-picture, Android shortcuts, live trip notifications and route imports |

Data coverage varies by provider. Transit delays, platforms, indoor floor plans, photos and fuel prices depend on the place or transit feed. Vehicle positions based on schedules or route segments can be estimates. A downloaded map area is for browsing cached maps. Online lookups and route calculation still need a connection.

## Data and privacy

- The Mapbox map requests tiles for the viewed area. Mapbox routing receives route coordinates when selected. The public token is stored in the app settings. [Mapbox terms](https://www.mapbox.com/legal/tos/)
- Optional Google features use public web endpoints for searches, nearby places, traffic, directions and place media. **Use Kite without Google** disables those features and Waze. The separate Mapbox preference remains available.
- Transitous, the open routing and search services, and optional roads, weather and camera feeds receive queries for their respective features. Saved places and local settings are stored on the device.
- See **Settings → About → Credits and data sources** for the providers in the app.

## Credits and data sources

Big thanks to the people and projects that made this possible.

| Project or provider | Used for |
| --- | --- |
| [Kite Maps](https://github.com/MatteoFX1/kite-maps), [Vela Maps](https://github.com/PimpinPumpkin/Vela) | The upstream app, Android map and navigation code, public Google integrations and the UK fuel feed |
| [Horizon Maps](https://github.com/benbishop028-arch/Horizon-Maps), [Publit](https://github.com/elija-7580/Publit) | Map and scenery references, and inspiration for the public transport experience |
| [Ausbloke's Kite fork](https://github.com/NotDiscordOfficial/kite-maps), [SABRE Plus](https://github.com/nicglazkov/highway-radar-sabre-plus) | Surveillance cameras, saved-place matching, GPS warnings and diagnostics, and Waze integration |
| [Mapbox](https://www.mapbox.com/), [MapLibre Native](https://maplibre.org/) | Mapbox Standard, map imagery and Directions, and the open map renderer. [Mapbox logo license](licenses/Mapbox-GL-JS.txt) |
| [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), [OpenFreeMap](https://openfreemap.org/), [OpenMapTiles](https://openmaptiles.org/) | Open vector maps, roads, points of interest, indoor map data and geometry |
| [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API), [Photon](https://photon.komoot.io/), [Postcodes.io](https://postcodes.io/) | OpenStreetMap queries, address lookup and UK postcodes |
| [FOSSGIS routing](https://routing.openstreetmap.de/), [OSRM](https://project-osrm.org/), [Valhalla](https://valhalla.github.io/valhalla/) | Routing, map matching and route details |
| [Google Maps](https://maps.google.com/), [Google Street View](https://www.google.com/streetview/), [Waze](https://www.waze.com/live-map) | Optional place data, search, traffic, directions, panoramas and road reports |
| [Transitous](https://transitous.org/), its feed contributors, [MBTA V3 API](https://api-v3.mbta.com/) | Itineraries, departure boards, real-time transit and supported Boston vehicle data |
| [Esri World Imagery](https://www.arcgis.com/home/item.html?id=10df2279f9684e4a9f6a7f08febac2a9), [Esri World Hillshade](https://services.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer), [AWS Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) | Fallback imagery, hillshade and terrain elevation |
| [Waymarked Trails](https://waymarkedtrails.org/), [Open-Meteo](https://open-meteo.com/) | Walking and cycling overlays, and route weather |
| [OpenHighways](https://openhighways.uk/), [Transport for London](https://api.tfl.gov.uk/), [Windy Webcams](https://www.windy.com/webcams), [DeFlock](https://deflock.me/) | Road cameras, scenic webcams and surveillance data |
| [Highway Radar](https://highwayradar.com/), [adsb.lol](https://adsb.lol/), [airplanes.live](https://airplanes.live/) | Optional heatmap and aircraft alerts |
| [Lucide](https://lucide.dev/), [Google Material Icons](https://github.com/google/material-design-icons) | Two alternate icon packs. [Bundled asset licenses](android/app/src/main/assets/map-icons/) |
| [Android](https://developer.android.com/), [Kotlin](https://kotlinlang.org/), [Jetpack Compose](https://developer.android.com/compose), [Coil](https://coil-kt.github.io/coil/), [OkHttp](https://square.github.io/okhttp/), [Gson](https://github.com/google/gson) | App platform, Material 3, images, HTTP and JSON |

The source code is available under [GPL-3.0](LICENSE). Individual dependencies, data providers and bundled assets have their own terms. This fork is an independent community project.

## Build locally

With the Android SDK and Java installed:

    cd android
    ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug

The app requests its own public Mapbox token at runtime. Personal tokens and signing keys belong outside the repository.
