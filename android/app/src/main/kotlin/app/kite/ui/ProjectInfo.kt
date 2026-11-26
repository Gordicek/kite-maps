package app.kite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kite.KType
import app.kite.interfaceAccentText
import app.kite.kt
import app.kite.primaryText
import app.kite.secondaryText

internal const val ANDROID_PROJECT = "https://github.com/Gordicek/kite-maps"
internal const val ANDROID_RELEASES = "$ANDROID_PROJECT/releases"

@Composable
internal fun ProjectStory() {
    Text(
        kt(
            "I'm Gordicek. I don't have a programming background. I built this Android fork of Kite Maps with ChatGPT 6.2 Sol because I wanted one app for driving, walking, transit and exploring places. It's a hobby project and still has plenty of bugs.",
            "Jsem Gordicek a programování nerozumím. Tenhle Android fork Kite Maps jsem vytvořil s pomocí ChatGPT 6.2 Sol, protože jsem chtěl jednu aplikaci na jízdu, chůzi, MHD i prohlížení míst. Je to projekt pro radost a pořád má spoustu chyb.",
        ),
        fontSize = KType.subheadline, color = primaryText, modifier = Modifier.padding(14.dp),
    )
}

@Composable
internal fun ForkChanges(close: () -> Unit) {
    val context = LocalContext.current
    val sections = listOf(
        kt("Map and appearance", "Mapa a vzhled") to kt(
            "Optional Mapbox Standard, satellite and offline maps, 3D terrain and buildings, hillshade, lamps and trees. Day, night and sunrise/sunset modes, wallpaper colors, editable palettes, interface colors and three icon packs with previews.",
            "Volitelný Mapbox Standard, satelit a offline mapy, 3D terén a budovy, stínování, lampy a stromy. Denní, noční a automatický režim podle slunce, barvy tapety, vlastní palety, barvy rozhraní a tři sady ikon s náhledy.",
        ),
        kt("Search and places", "Hledání a místa") to kt(
            "Google and OpenStreetMap search, place categories and a search-this-area control. Swipe through results on the map. Add shortcuts and lists. Browse reviews, photos, hours and venue details. Explore Street View with a map and supported buildings floor by floor.",
            "Hledání v Googlu i OpenStreetMap, kategorie a hledání v zobrazené oblasti. Přepínání výsledků posunutím karty, zkratky a seznamy. Recenze, fotky, otevírací doba a údaje o místech. Street View s mapkou a prohlížení pater v podporovaných budovách.",
        ),
        kt("Route planning", "Plánování tras") to kt(
            "Choose engines for driving, walking and cycling. Edit and drag stops into order, pick endpoints on the map, compare routes by time, tap a step on the map, save and share routes or open a Google Maps route link. Check forecast weather, elevation and road surfaces.",
            "Výběr enginu pro auto, chůzi i kolo. Úprava a přetahování zastávek, body z mapy, časy alternativ, náhled jednotlivých kroků, uložení a sdílení trasy i otevření odkazu z Google Maps. Předpověď na trase, výškový profil a povrch cesty.",
        ),
        kt("Driving and road alerts", "Jízda a dopravní hlášení") to kt(
            "Following camera and turn previews, lane guidance, roundabouts, road name and traffic on the route. Configurable Road Ahead strip for signals, crossings, cameras and Waze reports. Find and add stops along the route, skip the next stop or look for parking near arrival.",
            "Kamera sledující polohu, náhled dalších odboček, řazení do pruhů, kruháče, název silnice a provoz na trase. Nastavitelná lišta se semafory, přejezdy, kamerami a hlášeními Waze. Hledání a přidání zastávek podél trasy, přeskočení zastávky a hledání parkoviště u cíle.",
        ),
        kt("Walking, cycling and outdoors", "Chůze, kolo a výlety") to kt(
            "Walking guidance with a configurable side panel and compass, separate trail and path layers, water taps and other OpenStreetMap categories, elevation and surface details, straight-line distance with a two-finger hold.",
            "Pěší navigace s nastavitelným bočním panelem a kompasem, samostatné vrstvy stezek a cest, pítka a další kategorie OpenStreetMap, výška a typ povrchu, měření vzdušné vzdálenosti podržením dvou prstů.",
        ),
        kt("Public transport", "Veřejná doprava") to kt(
            "Color-coded trips and transfers, nearby departures, trip previews on the map, and guidance through walking, boarding, riding and transfers. Delays, platforms, entrances, disruptions, occupancy and live or estimated vehicle positions depend on the feed. Save trips and get departure reminders.",
            "Barevné spoje a přestupy, odjezdy v okolí, náhledy úseků a navigace pěšky, při nástupu, jízdě a přestupu. Zpoždění, nástupiště, vstupy, výluky, obsazenost a živé nebo odhadované polohy vozů závisí na zdroji dat. Uložení spojů a připomínky odjezdu.",
        ),
        kt("Android and Czech", "Android a čeština") to kt(
            "Android shortcuts and route links, picture-in-picture in either orientation, live navigation notifications, selectable driving marker, launcher appearance and Czech interface text, speech and dictation.",
            "Zkratky Androidu a odkazy na trasy, obraz v obraze na výšku i na šířku, živá navigační oznámení, volba ukazatele vozidla, vzhled ikony aplikace a čeština v rozhraní, hlasové navigaci i diktování.",
        ),
    )
    TripSheet(kt("Changes from Kite", "Změny oproti Kite"), kt("Done", "Hotovo"), close) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((title, detail) in sections) SettingsGroup(title) {
                Text(detail, color = primaryText, fontSize = KType.subheadline, modifier = Modifier.padding(14.dp))
            }
            Text(kt("Read the full list on GitHub", "Celý přehled změn na GitHubu"), color = interfaceAccentText,
                modifier = Modifier.fillMaxWidth().tap { context.open(ANDROID_RELEASES) }.padding(14.dp))
        }
    }
}

@Composable
internal fun ProjectCredits(close: () -> Unit) {
    val context = LocalContext.current
    val credits = listOf(
        Triple("Kite Maps · MatteoFX1", kt("Upstream project and app", "Původní projekt a aplikace"), "https://github.com/MatteoFX1/kite-maps"),
        Triple("Vela Maps · PimpinPumpkin", kt("Android foundation, Google web integrations and UK fuel data", "Základ Androidu, webové zdroje Googlu a britská data o palivech"), "https://github.com/PimpinPumpkin/Vela"),
        Triple("Horizon Maps", kt("Map styling and scenery references", "Inspirace pro styl mapy a 3D prvky"), "https://github.com/benbishop028-arch/Horizon-Maps"),
        Triple("Publit", kt("Transit interface inspiration", "Inspirace pro rozhraní MHD"), "https://github.com/elija-7580/Publit"),
        Triple("Ausbloke's Kite fork", kt("Surveillance cameras and diagnostics", "Kamery a diagnostika"), "https://github.com/NotDiscordOfficial/kite-maps"),
        Triple("SABRE Plus", kt("Waze live reports integration", "Integrace živých hlášení Waze"), "https://github.com/nicglazkov/highway-radar-sabre-plus"),
        Triple("Mapbox", kt("Standard, imagery, tiles and Directions", "Standard, snímky, dlaždice a plánování tras"), "https://www.mapbox.com/"),
        Triple("MapLibre Native", kt("Open map renderer", "Vykreslení otevřené mapy"), "https://maplibre.org/"),
        Triple("OpenStreetMap contributors", kt("Roads, places, trails and indoor map data", "Silnice, místa, stezky a data pro mapy budov"), "https://www.openstreetmap.org/copyright"),
        Triple("OpenFreeMap and OpenMapTiles", kt("Open vector basemap", "Otevřený vektorový podklad"), "https://openfreemap.org/"),
        Triple("Overpass API", kt("Map objects, controls and street lamps", "Mapové objekty, dopravní prvky a osvětlení"), "https://wiki.openstreetmap.org/wiki/Overpass_API"),
        Triple("FOSSGIS · OSRM and Valhalla", kt("Open routing and map matching", "Otevřené plánování tras a přiřazení k silnici"), "https://routing.openstreetmap.de/"),
        Triple("Photon and Postcodes.io", kt("Open address search and UK postcodes", "Otevřené hledání adres a britská PSČ"), "https://photon.komoot.io/"),
        Triple("Google Maps and Street View", kt("Optional places, search, traffic and directions", "Volitelná místa, hledání, provoz a trasy"), "https://maps.google.com/"),
        Triple("Waze and Highway Radar", kt("Optional road reports and traffic overlay", "Volitelná hlášení a dopravní vrstvy"), "https://www.waze.com/live-map"),
        Triple("Transitous", kt("Trips, departures and real-time transit data", "Spoje, odjezdy a živá data MHD"), "https://transitous.org/"),
        Triple("MBTA", kt("Vehicle positions and occupancy where supported", "Polohy a obsazenost podporovaných spojů"), "https://api-v3.mbta.com/"),
        Triple("Esri and AWS Terrain Tiles", kt("Fallback imagery, hillshade and terrain elevation", "Záložní snímky, stínování terénu a nadmořská výška"), "https://registry.opendata.aws/terrain-tiles/"),
        Triple("Waymarked Trails", kt("Hiking and cycling overlays", "Turistické a cyklistické vrstvy"), "https://waymarkedtrails.org/"),
        Triple("Open-Meteo", kt("Route weather forecasts", "Předpověď počasí na trase"), "https://open-meteo.com/"),
        Triple("OpenHighways, TfL and Windy", kt("Road and scenic cameras", "Silniční kamery a webkamery"), "https://www.windy.com/webcams"),
        Triple("DeFlock", kt("Surveillance camera data and community", "Data o dohledových kamerách a komunita"), "https://deflock.me/"),
        Triple("adsb.lol and airplanes.live", kt("Optional aircraft alerts", "Volitelná letecká upozornění"), "https://adsb.lol/"),
        Triple("Lucide", kt("Open-source outline icons", "Otevřená sada obrysových ikon"), "https://lucide.dev/"),
        Triple("Google Material Icons", kt("Open-source rounded icons", "Otevřená sada zaoblených ikon"), "https://github.com/google/material-design-icons"),
        Triple("Android, Kotlin and Jetpack Compose", kt("App platform and Material 3", "Platforma aplikace a Material 3"), "https://developer.android.com/compose"),
    )
    TripSheet(kt("Credits and data sources", "Poděkování a zdroje dat"), kt("Done", "Hotovo"), close) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SettingsGroup {
                for ((name, detail, url) in credits) {
                    Column(Modifier.fillMaxWidth().tap { context.open(url) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(name, color = interfaceAccentText, fontWeight = FontWeight.SemiBold)
                        Text(detail, color = secondaryText, fontSize = KType.caption)
                    }
                    HorizontalDivider(Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}
