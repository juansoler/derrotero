# Third-party notices

Derrotero is distributed under the Apache License 2.0 (see `LICENSE`).
It uses the following third-party components. The versions listed are the
ones declared in `gradle/libs.versions.toml` at the time of writing.

## Apache License 2.0

The following components are licensed under Apache-2.0. The full license
text is in `LICENSE` and at <https://www.apache.org/licenses/LICENSE-2.0>.

| Component | Project |
|---|---|
| AndroidX Core, Lifecycle, Activity, Navigation, DataStore, DocumentFile | <https://developer.android.com/jetpack/androidx> |
| Jetpack Compose (UI, Material 3, Material Icons) | <https://developer.android.com/jetpack/compose> |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | <https://kotlinlang.org/> |
| OkHttp | <https://square.github.io/okhttp/> |

## BSD 2-Clause

### MapLibre Native Android SDK

Copyright (c) MapLibre contributors.
<https://github.com/maplibre/maplibre-native>

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

## BSD 3-Clause

### NanoHTTPD

Copyright (c) 2012-2013 NanoHTTPD contributors.
<https://github.com/NanoHttpd/nanohttpd>

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.

## Eclipse Public License 1.0 (tests only)

JUnit 4 is used only when running the unit tests and is not distributed in
the application. <https://junit.org/junit4/>

## Map data and tile providers

Derrotero can display map data from these providers. Their terms and
attribution requirements apply to that data:

- **OpenStreetMap** — © OpenStreetMap contributors, ODbL.
  <https://www.openstreetmap.org/copyright>
  Tile usage policy: <https://operations.osmfoundation.org/policies/tiles/>
  Bulk downloading is not allowed; the app disables offline download for this
  provider.
- **Open Waters / Seamap** — © Open Waters, CC BY 4.0.
  <https://map.openseamap.org/> · <https://www.openstreetmap.org/copyright>
- **OpenSeaMap** — © OpenSeaMap contributors, CC BY-SA.
  <https://www.openseamap.org/>
- **EMODnet** — EMODnet Bathymetry and human activities data, open data.
  <https://emodnet.ec.europa.eu/>

Attribution is shown on the map. The user is responsible for complying with
each provider's terms when adding custom XYZ tile sources.

## Compatibility references

OpenCPN, Navico, B&G, Simrad, Lowrance, Raymarine and Garmin/Navionics are
mentioned only to describe compatibility or comparable features. They are not
dependencies of this project, no code from them is included, and Derrotero
is not affiliated with or endorsed by any of them.

## Test data

- `app/src/test/resources/s57/US5CA13M.000` — NOAA Electronic Navigational
  Chart (public domain in the United States), used as a test fixture.
- `tools/samples/US5CA13M.*` — same NOAA ENC, used as a sample chart.
- `tools/samples/santander-test.*` — synthetic chart generated by
  `tools/make_sample_charts.py` for this project.
