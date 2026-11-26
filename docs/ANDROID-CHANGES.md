# What changed in Kite Driving for Android

Kite Driving started from Kite Maps 0.7.16 and incorporated the relevant Android changes from 0.7.17. This list describes features added or substantially reworked in the Android fork. Existing Kite and Vela features are credited in the [README](../README.md#credits-and-data-sources).

## Basemap, terrain and appearance

- Optional native Mapbox Standard renderer and public token setup on first launch, with a MapLibre and OpenFreeMap fallback. Switch the provider independently of the Google setting.
- Mapbox and Esri satellite imagery, 3D terrain, hillshade, building shadows, 3D buildings and trees, and street lamps with night lighting. Lamp data uses mapped positions and can estimate positions on nearby mapped roads.
- Light, dark and time-based map lighting, with app appearance and map lighting controlled separately. Day and night palettes, a color picker with previews, and Material 3 Expressive colors from the Android wallpaper.
- Configurable interface accents and opaque panels, a reorganized layers panel, and remembered expansion for the on-map layer controls. Mapbox attribution positioned to leave room for navigation.
- Three map-icon sets with previews, separate sizes for Google and OpenStreetMap markers, search results and Waze, and adjustable Road Ahead positions and styles.
- Offline map management by country and region with download progress and storage management. The cached basemap is available for offline browsing.

## Search, places and saved items

- Full-screen search and Czech dictation, explicit OpenStreetMap-only search, a search-this-area control, map category chips or a collapsible category button, and recent-search swipe actions.
- Independent Google and OSM overlays, selectable OSM categories and complementary POIs such as drinking water, toilets, park benches, shelters and small car parks.
- Result-specific pins and numbering, nearby-first handling for repeated business names, open-now filtering in supported lists, and caching and prioritization so ambient markers render earlier and move less during browsing.
- Compact, middle and full place cards, horizontal navigation between results, and clearer picked-place markers for OSM and Google.
- Place cards with opening hours, address, phone, website, review stars and photos. Review and photo pages, author and photo details where available, and photo zoom.
- Street View with panorama navigation and an expandable interactive map showing location and viewing direction.
- Supported indoor floors, venues and facilities from mapped data, with user location, compass, and the option to navigate to a place inside. Availability varies by building.
- Share a Google Maps place link, the place name, or coordinates. Save places directly to a list or shortcut, rename them, and organize them in named lists.
- Additional named Home/Work-style cards, Android launcher shortcuts and separation between saved places and saved routes. Optional fuel prices where a feed covers the place.

## Plan a route

- Separate engine selection for driving, cycling and walking, including Mapbox, Google and open-data routing when supported. Automatic mode and fallback logic account for provider coverage.
- Modify the start and destination using the normal search UI, choose either on the map, and drag intermediate points to reorder them.
- Save and share routes, import a Google Maps directions link with its waypoints and mode, and select available Android deep links through the launcher.
- Frame alternative routes together. Choose them with tappable duration badges placed along the visible route branches. Preview individual route steps on the map.
- Route comparisons with surface information, traffic, weather at expected arrival times, and an interactive elevation profile including driving. Selecting a surface section highlights that part of the route.
- Search for stops over a planned route as well as an active one. Browse nearby alternatives without losing the previous results.

## Drive

- More consistent recentering at guidance start, 2D and 3D persistence, independent compass, navigation zoom and positioning, cross-street labels, one-way arrows, and the current road near the vehicle.
- Turn-by-turn banner and lane guidance with swipeable turn previews, verified roundabout geometry when available, and earlier highlighting for motorway exits. Configurable on-map turn arrows, vehicle marker and controls.
- Traffic shown on the navigation line, and a configurable Road Ahead strip for traffic, lights, stops, crossings, speed cameras and Waze reports. Reports can be tapped on the map, and nearby reports can be expanded from a group.
- Waze kinds, map and strip icon sizes, Czech text and voice, and a collapsible police-risk indicator. Traffic controls are matched against the active route where data permits.
- Along-route search with numbered pins, expandable place cards, map and list views, open-now filtering, and add-stop selection. Add a stop by pin or long press, remove the next stop, and optionally show selected Google or OSM categories while navigating.
- Parking prompt near arrival, faster-route offers, portrait or landscape picture-in-picture and Android live navigation notifications.
- Original arrow plus Alfa 147 and van vehicle markers. Position, search and sheet animations have been revised across releases.

## Walk, cycle and use public transport

- Choose an open-data walking engine with fallback where data is missing. Walking shares the navigation layout and has a configurable side strip for upcoming events.
- Separate hiking, cycling, footpath and bike-path layers, OSM categories for outdoor facilities, elevation, surfaces and straight-line distance from a two-finger hold.
- Public transport cards with colored lines and leg lengths, expandable changes and stops, tappable leg previews, map overview and a draggable itinerary.
- Transit guidance through walking to the stop, boarding, following the ride, getting off and walking between transfers. Manual controls remain available during guidance.
- Nearby departures and stations, selectable connections on the map, trip geometry corrections, and station entrances only where mapped. Platforms, delays, service notices, disruption, occupancy and live positions depend on the transit feed. Estimates are identified on the map.
- Saved trips for later, departure reminders and an optional learned walking pace. The experimental Golemio token integration has been removed. Transitous remains the principal transit source.

## Android and maintenance

- Czech language selection, map labels, turn instructions and voice input. Material 3 styling and accessible labels in the main map screens.
- Android shortcuts and deep links, selectable launcher icon and name, live notifications outside the app, and navigation picture-in-picture.
- Work to reduce cold-start crashes and map stalls, preserve camera ownership during guidance, prefetch data along routes, defer background overlays during gestures and keep Google place markers stable.
- Dedicated About pages with a summary of the Android fork, data attributions, and diagnostics you can review before sharing.

## 0.8.19

- Walking is back under Route engines with driving and cycling.
- Route time badges prefer separated stretches around the middle of the routes and are positioned inside the visible map. Nearby badge taps have a larger hit area.
- Route badge geometry is calculated in the background. During map movement, nonessential road-report and camera overlay work waits until the map settles.
- First launch explains Mapbox and takes an optional public access token. The app includes the story behind the fork, change summary and credits.
