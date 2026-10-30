# Kettler edzőapp

Android-app egy Kettler Vito XL elliptikus trénerhez: edzésképernyő, félig átlátszó overlay-sáv más appok (pl. Kodi) fölött, profilok, edzésnapló, edzésmódok és programok. 0.6 óta súlyzós edzés modul is van. Következő nagy lépés: központi adatbázis (lásd lent).

## Kommunikáció a felhasználóval
- Magyarul, lazán, haverként, helyes helyesírással. Röviden, lényegre törően.
- Ha valamiben nem vagy biztos, mondd ki. Soha ne találj ki semmit (pl. ellenállás → watt nincs, kalória pánt nélkül csak becslés).
- A felhasználó nem Android-fejlesztő. ESPHome-ot, Home Assistantot ismer. A lépéseket kezdőnek is követhetően írd le.
- **Munkamód:** gyakran sorolja a változtatáskéréseket. Ilyenkor csak írd fel és erősítsd meg őket számozva; megvalósítani csak akkor kezdd, ha szól („kezd el”, „csináld”).
- A felugró ablakok helyett teljes képernyős, az app stílusában tervezett oldalakat szeret (bal oldalt cím/menü, jobb oldalt tartalom).

## Hardver
- Tréner: Kettler Vito XL (konzol ST2510-8 / ST2550-8, -9 / ST2551-8). Az ellenállás kézi fékerő-állítóval megy, a konzol nem ismeri → watt nem számolható.
- Kézikönyv: 60 fordulat/perc = 9,5 km/h → 1 fordulat = 2,639 m. Energia: 1 km = 352 kJ (közepes szint). Max. pulzus = 220 − életkor. Kezdő program: lásd `Training.kt`.
- Sebességérzékelő: a konzol 2 pólusú reed-csatlakozója, párhuzamosan az ESP32 GPIO27-re kötve (belső felhúzás). **10 impulzus / fordulat** (kalibrálva a konzol RPM-jéhez).
- ESP32: MH-ET LIVE MiniKit, ESPHome, a konfiguráció: `esphome/kettlercontrol.yaml` (a felhasználó az ESPHome Builderben szerkeszti, WiFi-n tölti fel).
- Pulzuspánt: Magene H64 (BLE 0x180D/0x2A37). Terv: az ESP32 olvassa (ESPHome `ble_client`), az app nem használ Bluetootht. Még nincs bekötve.
- Tablet: Android 11, fekvő helyzetben a trénerre szerelve, töltőn. Kodi fut rajta (a Kodi saját gombjai az Android navigációs sáv alá esnek, ezt a felhasználó elengedte).

## Adatkapcsolat az ESP32-vel
- ESPHome `web_server` (version 3), SSE: `http://<ip>/events`. Az ESP IP-je: 192.168.1.230.
- Az ESP bontja a lassú SSE-klienst (logban: „Event source connection closed”), ezért: a yamlban `power_save_mode: none`, a fordulatszám/táv `throttle: 0.5s`, a szenzornapló INFO szinten; az app (`EspClient`) azonnal újrakapcsolódik, és csak 5 mp kiesés után jelez „Nincs kapcsolat”-ot. A `WorkoutService` wifi-zárolást tart (WAKE_LOCK), a Beállítások → Kapcsolat oldalon az utolsó 8 megszakadás látszik. 0.8.17-tel 5 perc tekerés alatt nem volt leszakadás (2026-10-29).
- Az eseményazonosítók a szenzorok nevéből jönnek (UTF-8): `sensor/Fordulatszám`, `sensor/Sebesség`, `sensor/Táv` → **a szenzorokat nem szabad átnevezni**. A szám a `value` mezőben van.
- A táv az ESP összesített értékéből jön, az app az edzés indításakor eltárolja és ahhoz képest számol (ESP-újraindulást is kezel).
- Az app a címet a Beállítások → Kapcsolat oldalon kéri („Külső eszköz IP-címe”). A `.local` név Android 11-en valószínűleg nem oldódik fel, ezért IP kell.

## Fordítás és kiadás
- Nincs helyi Android SDK. A fordítás GitHub Actionsben fut (`.github/workflows/apk.yml`) minden `main`-re pusholáskor.
- Repo: `DougButabi/kettler-edzo` (public). A kész APK állandó linkje (a felhasználó a tableten innen telepít):
  `https://github.com/DougButabi/kettler-edzo/releases/download/legujabb/kettler-edzo.apk`
- Frissítés az appból (Beállítások › Névjegy › Frissítés keresése, `Updater.kt`): a kiadás címéből („Legújabb build (#N)”) olvassa a sorszámot, összeveti a versionCode-dal, letölti az APK-t és `PackageInstaller`-rel telepíti (`UpdateReceiver` nyitja meg a megerősítést). A kiadás címének formátumát ezért ne változtasd meg a workflow-ban. Emulátoron a GitHub nem érhető el (a PC hálózata saját tanúsítvánnyal bontja a HTTPS-t), ezt csak valódi eszközön lehet kipróbálni.
- Aláírás: fix kulcs a `keystore/kettler.jks`-ben (az első Actions-futás hozta létre). Ne cseréld, különben a frissítés nem települ az előző verzióra.
- `versionCode` = Actions run number, `versionName` = `0.x.<run>` (`app/build.gradle.kts`). Új funkciócsomagnál emeld a középső számot.
- Munkafolyamat: commit → push a `main`-re (a felhasználó így kapja az APK-t) → `gh run watch <id> --exit-status`, hiba esetén `gh run view <id> --log-failed`, keresd az `e:` sorokat. A git/gh hálózati parancsok a Claude Code sandboxából nem érik el a GitHubot, sandbox nélkül kell futtatni őket.
- Verziók: AGP 8.7.3, Kotlin 2.1.0, Gradle 8.11.1 (a workflow telepíti), compileSdk/targetSdk 35, minSdk 26. Külső könyvtár nincs (SQLite, HttpURLConnection, org.json, sima View-k).

## Az app felépítése (`app/src/main/java/hu/kettleredzo/app/`)
- `KettlerActivity` – minden képernyő alapja: rendszersávok elrejtése, és számolja, látszik-e az app (ilyenkor az overlay rejtve marad).
- Útvonal: `ProfileActivity` (indító, profilválasztó) → `SportActivity` (elliptikus vagy súlyzós) → `MainActivity` (elliptikus edzésképernyő) vagy `WeightsActivity` (súlyzós kezdőlap).
- Elliptikus: `ModeActivity` (edzésmód), `SettingsActivity` (menük: Kapcsolat/Kijelző/Edzés/Felhasználók/Névjegy), `ProfileEditActivity`.
- Súlyzós (0.6 óta): `WeightsActivity` (kezdőlap az elliptikus edzésképernyő mintájára: bal oldalt a `sulyzos.webp` fotó, jobb oldalt a kiválasztott nap gyakorlatai, alul napválasztó + Indítás/Folytatás), a háttérfotó elrendezése közös: `ui.photoArea`.
- **Beállítások (0.8 óta egységes):** minden sport kezdőlapján jobb felül NAPLÓ + BEÁLLÍTÁSOK. A `SettingsActivity` `EXTRA_SPORT` szerint csak az Általános csoportot (Felhasználók, Hangjelzések, Névjegy + Kilépés) és az adott sport pontjait mutatja: elliptikus (Kapcsolat, Kijelző, Fékfokozatok), súlyzós (Edzésterv, Gyakorlatok, Gépek – a felhasználó kérésére csak itt, nincs külön gomb a kezdőlapon), kerékpár (Automatikus szünet, Térkép, Jelzések, Kijelző). A logóra / sportnévre koppintva vissza a sportválasztóhoz, a névre koppintva profilváltás. `PlanDayActivity` (egy nap szerkesztése: gyakorlatok, pihenő, sorozatok egyenként), `LiftActivity` (edzés közben: pipálás, ismétlés/súly állítása, pihenő-visszaszámlálás, befejezéskor emelési javaslat), `CatalogEditActivity` (gép vagy gyakorlat: név, kép, gép). `Weights.kt` – adatosztályok, `Lifting` (MET-es kalóriabecslés, súlylépések), `Catalog` (beépített gépek/gyakorlatok, kész tervek), `Images` (kép betöltése, saját kép importálása a `files/kepek` mappába).
- Kerékpár (0.7 óta, telefonon): `BikeActivity` (kezdőlap a többi mintájára, fotó a profil neme szerint: `kerekpar.webp` / `kerekpar_ferfi.webp`; edzésmód a `ModeActivity`-vel, `EXTRA_SPORT = bike`, program nélkül), `RideActivity` (tekerés közben az elliptikus mintájára nagy számok, a képernyő ~40%-án OpenStreetMap-térkép – állóban felül, fekvőben balra; a követés minden GPS-helynél `setCenter`, nem animáció, mert a gyakori `animateTo` egymást szakítja meg), `TrackActivity` (a naplóból: mentett útvonal térképen), `BikeService` (előtérszolgáltatás `location` típussal, `LocationManager` GPS, kikapcsolt kijelzővel is mér), `Bike.kt` (`BikeState`: táv csak 3 km/h fölött, automatikus szünet, szintemelkedés 3 m-es küszöbbel, útvonalpontok; `Cycling.met` a kalóriához). A tekerés a `workouts`-ba kerül (`sport = bike`, `elev_gain`), az útvonal a `tracks` táblába (JSON). Térkép: `org.osmdroid:osmdroid-android` – **ez az egyetlen külső könyvtár**; csempék az app saját mappájában, user-agent = csomagnév.
- Közös napló: `LogActivity` + `BarChartView` (minden sport, a diagram edzésidő percben).
- **Álló / fekvő:** tableten (smallestWidth ≥ 600 dp) mindig fekvő, telefonon a forgatás szerint (`KettlerActivity`, a manifestben nincs `screenOrientation`). Minden képernyő mindkét irányra kész: `ui.portrait`, `ui.splitRoot/sideLp/mainLp/paneLayout`, menüknél `ui.nav/navButton/navLp`, edzésképernyőknél `ui.homeScreen/topBar/logo/bottomBar`. Forgatáskor az Activity újraépül, ezért a szerkesztés alatti állapotot `onSaveInstanceState`-ben mentjük (új képernyőnél is így csináld).
- **Helyi fordítás és emulátoros ellenőrzés (ezen a Windows gépen):** minden a `C:\Android` alatt van, ékezet nélküli útvonalon (az ékezetes „Tamás” útvonalon az emulátor és a Gradle nem működik). `C:\Android\KettlerEdzo` a repóra mutató junction. Virtuális eszközök: `Telefon` (pixel_7, 1080×2400) és `Tablet` (pixel_tablet), indítás: `ANDROID_SDK_ROOT=C:\Android\Sdk`, `ANDROID_AVD_HOME=C:\Android\Avd`, `emulator -avd Telefon` (a tablet `-port 5556`). Szkriptek: `build.ps1` (fordít és mindkét futó emulátorra telepít), `go.ps1 <sport>` (újraindítja az appot és belép a Tamas profillal), `tap.ps1 "<szöveg>"` (koppintás szöveg alapján), `shot.ps1 <név>` (képernyőkép a `C:\Android\Shots` mappába). Az emulátor nem éri el az internetet (térképcsempék nem töltődnek be).
- **Telefonos nézet (a user kérése: a telefonon változtatunk, a tablet maradjon):** `ui.phone`. Állóban a `ui.text` a 40 alatti méreteket duplázza és félkövérre vált (a nagy számok maradnak), a gombok (`button/choice/pill/pillButton/roundButton`) 1,5×. A beállítás-jellegű képernyők (`Settings`, `CatalogEdit`, `PlanDay`, `ProfileEdit`) `ui.settingsMode()`-ot hívnak: ott csak a `note` nő 1,5×-re. `ui.edge`: plusz margó telefonon a lekerekített sarkok miatt. A gép/gyakorlat képek (`ui.picture`) telefonon állóban 1,5×. Telefonon nincs overlay-sáv (és a Kijelző beállítás sem). Tekerésnél állóban felül a számok (40%), alul a térkép (60%).
- `WorkoutService` – előtérszolgáltatás: SSE-kapcsolat (`EspClient`), 1 mp-es tick (mozgásidő, programszakaszok, cél ellenőrzése, hangok), overlay-sáv. Csak az elliptikus edzéshez kell.
- `KettlerState` – közös állapot (csak fő szálról). `Prefs` – SharedPreferences + formázók. `Training.kt` – edzésmódok, kezdő program, `Beeper`.
- `Db.kt` – SQLite, v3. Táblák: profiles, workouts (sport, title, in_progress oszlopokkal; a súlyzós edzés is ide kerül), equipment, exercises, plan_days, plan_items (a sorozatok JSON-ban), workout_sets. **Minden táblában `uuid`, `updated_at`, `deleted`** a későbbi ULC-szinkronhoz: törlés csak megjelöléssel (`softDelete`), mentés a `save()`-en át (ez adja az uuid-t és az updated_at-et). Új táblánál is így csináld. A folyamatban lévő súlyzós edzés `in_progress = 1`, a napló ezt nem mutatja.
- `Ui.kt` – közös stílus: színek, a 904 px magas mintaterv pixeleiben megadott méretek (`ui.px/ipx`), `text`, `label`, `note`, `sidePanel`, `field`, `button`, `choice`/`setChosen`, `pill`, `pillButton`, `picture` (kép kártyán, kép nélkül kezdőbetű), `stepper` (−/+). Új képernyőnél ezeket használd.
- Képek: `res/drawable-nodpi/trener.webp` (nő) és `trener_ferfi.webp` (férfi) a profil neme szerint; `sulyzos.webp` (a felhasználó képe a súlyzós edzéshez); `wger_*.webp` gyakorlatrajzok, **mind egységes fekete-fehér Everkinetic-vonalrajz** (CC-BY-SA, forrás: github.com/everkinetic/data `src/images-web`, illetve wger.de; a felhasználó a színes/eltérő stílusú rajzot nem kéri). Új gyakorlathoz is innen válassz. A szerző a Névjegyben és a `Catalog.IMAGE_CREDITS`-ben; adaptív app-ikon a `mipmap-anydpi-v26`-ban.

## Buktatók
- Kotlin: egy Activity-tulajdonság ne legyen azonos nevű egy View-taggal (pl. `right`, `left`). `View.apply {}` blokkon belül a View tagja nyer → furcsa típushiba.
- Egy osztálytulajdonság ne legyen azonos nevű egy felső szintű függvénnyel, amit a tulajdonság hív (pl. `modeLabel`).
- Fekvő módban a billentyűzet teljes képernyős lenne: EditText-eken `compactKeyboard()`, az Activity-n `adjustPan`.
- Az overlay csak futó edzés közben látszik, és kell hozzá a „Megjelenítés más alkalmazások felett” engedély.
- `letterSpacing`-es, kipontozott feliratokat az Android levághat. Szűk helyen `ellipsize = null` és egy kis ráhagyás kell.

## Súlyzós modul (0.6-ban elkészült)
- Állapot 2026-10-29: a súlyzós modul első változata kész (lásd fent). Pulzuspántos (Keytel) kalória még nincs, mert a pánt nincs bekötve; addig MET-becslés. A ULC-szinkron elkészült, lásd lent.
- **Második sport: súlyzós edzés.** A felhasználó eszközei: fekvenyomó pad, Scott-pad, tárcsás lehúzó, kézi súlyzók.
  - Alapadatok: **gépek** és **gyakorlatok** névvel és képpel, menüből bővíthetők. Kép: alapból a wger nyílt adatbázis rajza (CC-BY-SA, szerzőt fel kell tüntetni a Névjegyben), lecserélhető saját képre.
  - **Edzésterv:** betűjeles napok (A, B, C…). Naponként gyakorlatok, gyakorlatonként sorozatok egyenként (ismétlés + súly, eltérhetnek, pl. 1×8×15 kg, aztán 3×8×40 kg). Lehet kész programból indulni, utána szerkeszthető.
  - **Edzés közben:** sorozatonként pipa, vagy a ténylegesen elvégzett ismétlés/súly beállítása. Pihenő-visszaszámlálás hangjelzéssel. Ha minden sorozat teljesült, felajánl súlyemelést a következő alkalomra.
  - **Kalória:** pánttal pulzusból (Keytel), pánt nélkül is legyen becslés (pl. MET × testsúly × idő; jelölni, hogy becslés).
  - Közös napló a két sportnak. Később jöhet kerékpározás modul is.
## Szinkron a ULC-vel (2026-10-29, 0.8.24-től)
- **Döntések:** teljes, két irányú szinkron (telefon és tablet közös profilokkal, edzéstervvel, saját gyakorlatokkal/gépekkel, naplóval); csak otthon, helyi hálózaton (nincs VPN); a ULC csak megjelenít (nincs webes tervszerkesztés); nincs súlynapló-kapcsolat. Az edzésprofilok a ULC-ben külön élnek, nem ULC-felhasználókhoz kötve.
- **App:** `Sync.kt`. Felküldi az `updated_at > Prefs.syncPushedAt` sorokat (a folyamatban lévő edzés és sorozatai nem mennek), lehozza a `rev > Prefs.syncRev` sorokat. Hivatkozások a szerver felé uuid-ként (`profile_id` → `profile_uuid` stb.). Ütközés: későbbi `updated_at` nyer. Saját képek (`file:kepek/…`) base64-ben fel, hiányzók `GET /api/edzes/kep/<név>`-vel le.
- **Összefésülés (álnevek):** a szerver az ismeretlen uuid-jú sort természetes kulcs szerint egy meglévőbe olvasztja (profil: név + születési év; nap: profil + betű; tétel: nap + gyakorlat + pozíció; katalógus: seed_key), és visszaküldi a régi → új uuid párt; az app átnevezi, vagy ha az új már megvan, átirányítja a hivatkozásokat és törli a régit.
- **Beépített katalógus:** rögzített uuid (`seedUuid`: `seed-eq-<kulcs>`, `seed-ex-<kulcs>`; DB v6 migráció), hogy eszközönként ne duplázódjon.
- **Első indítás (`SetupActivity`):** eszközkulcs nélkül az app nem indul (ProfileActivity átirányít). Bejelentkezés a ULC-felhasználóval + eszköztípus: **közös** (profilválasztóval, pl. tablet) vagy **személyes** (egy profilhoz kötve, pl. telefon; a belépés után a ULC-profilok közül vagy újat választ). Személyes eszközön: nincs profilválasztó/„← PROFILOK”, a Felhasználókban csak a saját profil, az nem törölhető, és a szerver csak a saját profil adatait küldi le (`profile_uuid` a szinkron kérésben). Mód vagy profil cseréje: kulcs visszavonása a ULC Edzés oldalán → az app kijelentkezik, és újra a Setup jön.
- **Láthatatlan szinkron:** nincs Szinkron menüpont; a Névjegy alján egy sor mutatja az utolsó szinkront.
- **Beépített katalógus időbélyege:** a módosítatlan beépített gép/gyakorlat `updated_at = Catalog.SEED_TIME` (1), DB v7 migráció is ezt állítja, hogy egy frissen telepített eszköz ne írja felül a máshol átnevezett/átképezett elemeket.
- **Mikor:** az app indításakor (ProfileActivity), edzés mentése után (elliptikus, kerékpár, súlyzós), és a Beállításokból kilépve; kézzel: BEÁLLÍTÁSOK → Szinkron. Csendben kihagyja, ha a ULC nem érhető el.
- **Beállítás:** (2026-10-29 este óta a Setup képernyőn, lásd fent) ULC címe (alap `http://192.168.1.78:8091`), és egyszeri bejelentkezés a ULC-felhasználóval (`POST /api/edzes/login`). A ULC eszközkulcsot ad (az app ezt tárolja, a jelszót nem); visszavonni a ULC **Edzés** oldalán lehet, ekkor az app magától kijelentkezik.
- **ULC oldal:** `webapp/src/lib/training.js`, `routes/edzes.js`, `views/edzes.ejs`, `views/edzes-reszletek.ejs` (Ultimate Life Control projekt). Táblák: `edzes_*`, `edzes_meta` (rev), `edzes_devices`. Képek: `data/EdzesKepek`.
- **Emulátoros teszt:** az emulátor nem éri el a helyi hálózatot, ezért `node C:\Android\proxy.js` (127.0.0.1:8091 → NAS) és `adb reverse tcp:8091 tcp:8091`, az appban a cím `http://127.0.0.1:8091`. A bejelentkezéshez valódi ULC-jelszó kell, azt a felhasználó adja meg (a `C:\Android\setsync.ps1` még a régi, kulcsbeírós oldalhoz készült). Teszt után az éles ULC-ből töröld a tesztadatot és a kulcsot.

## Pulzusmérő és célok (2026-10-30, DB v8)
- Pulzus: `HeartRate.kt`, szabványos BLE pulzus-szolgáltatás (0x180D / 0x2A37), a felhasználónak Magene pántja van. Párosítás: Beállítások › Általános › Pulzusmérő (keresés csak 0x180D-t hirdető eszközökre; cím/név a Prefs `hrAddress`/`hrName`-ben). A sportválasztó és az edzésképernyők `onResume`-ja rácsatlakozik, és a kapcsolat megmarad (a pánt lassan, akár percekig csatlakozik újra); csak a Kilépés az appból bontja (`HeartRate.disconnect`). A szív ikon (`HeartRate.heartColor`): piros = jön a pulzus, villogó piros = csatlakozik, alapszín = nincs pánt.
- Munkamenet: `HeartRate.begin(kulcs, profil) { számít-e most }` a sport indításakor; a mérésekből átlag/max, 5 mp-es görbe (`workouts.hr_samples`, JSON bpm-tömb) és Keytel-kalória. `HeartRate.kcal(becslés, mozgásidő)`: a pulzusos időre a Keytel-érték, a kiesett részre a sport saját becslése. Naplóban: koppintásra `HrActivity` (görbe), kerékpárnál a `TrackActivity`-ből.
- Célok: `goals` tábla (profile_id, sport = elliptical/bike/weights/all, metric = km/min/kcal/count/sets, period = day/week, target), `Goals.kt`, szerkesztés: `GoalsActivity` (a sportválasztó célsorára koppintva vagy a profil szerkesztéséből). Haladás: `Db.goalValue` (a hét hétfőn kezdődik). Szinkronizál (ULC: `edzes_goals`, természetes kulcs profil+sport+mérték+időszak).
- Emulátoron nincs pánt, a BLE-részt (csatlakozás, mérés) csak valódi eszközön lehet kipróbálni.

## Beállítások elrendezése
- Telefonon állóban a menü külön oldal, egymás alatti gombokkal; egy pont teljes képernyőn nyílik, „← Menü” (és a vissza gomb) visz vissza (`menuMode`). Fekvőben és tableten bal oldalt a menü, 30%-kal kisebb gombokkal (`ui.compact`), a „← VISSZA” a lap tetején a KETTLER mellett (`ui.backOnTop`, a Céloknál is), a menü alján a Kilépés az appból.
- Kapcsolat: csak „● Kapcsolódva” / „Nincs kapcsolat”, a részletek (cím, hiba, megszakadások) a „Napló” gomb mögött (`showConnectionLog`).
- Az Edzésmód-választó (`ModeActivity`) telefonon állóban ugyanígy: lista, majd a mód teljes képernyőn.
- Emulátoros teszt bejelentkezés nélkül: debug build (`assembleDebug`, `ANDROID_USER_HOME=C:\Android\UserHome`), és `run-as` a `shared_prefs/beallitasok.xml`-be egy próbakulccsal. Utána a release-t vissza kell telepíteni (más az aláírás).

## Rutin profilonként (elliptikus, DB v9)
- `profiles.routine` (JSON, szinkronizál; ULC `edzes_profiles.routine`), `Routine.kt`: fülhallgató (Bluetooth-cím) és médiahangerő %. Szerkesztés: profil szerkesztése › „Rutin beállítása” (`RoutineActivity`). A mentés külön (`Db.saveRoutine`), hogy a profil mentése ne írja felül.
- Az elliptikus képernyő megnyitásakor fut (`MainActivity.runRoutine`, nem fut edzés közben és forgatáskor): beállítja a hangerőt, és figyelmeztet, ha a fülhallgató nincs a hangkimenetek között.
- 2026-10-30-án volt app-indítás (YouTube/Kodi JSON-RPC) és Rutin gomb is, a felhasználó kérésére kivettük (cdee893 commitban megvan, ha kellene).

## Hangos visszajelzés (2026-10-30)
- Elliptikusnál és kerékpárnál céllal (táv, kcal, idő, program): negyed / harmad / fél (Beállítások › Hangjelzések, `VoiceEvery`), 95%, utolsó km / perc, cél. Férfi vagy női hang (Prefs `voiceMale`). `Voice.kt`: `Voice.say(kulcs)` véletlen változat, audio focus „duck”-kal; `Milestones` kihagyja a mérföldkövet, ha a következő 30 mp-en belül jönne (a tempóból becsülve).
- A hangfájlok előre legenerálva: `tools/hangok.py` (Microsoft Edge felolvasó, `pip install edge-tts`, hangok hu-HU-TamasNeural / hu-HU-NoemiNeural) -> `res/raw/v_<m|f>_<kulcs>_<n>.mp3`. Új mondatnál a listát bővítsd, és futtasd újra (a meglévőket nem írja felül).

## Előzmény: a központi adatbázis eredeti terve
- **Központi adatbázis:** több eszköz (tablet + Android telefonok), akár házon kívül is.
  - A felhasználónak van egy saját, webes felületű programja (**ULC**, MySQL-lel) a desktop PC-n. Az edzésadatok oda kerüljenek, a webes felületen nagyjából minden látszódjon és szerkeszthető legyen. Hogy ez min fut, és hol van a MySQL (mini PC vagy NAS), azt még ki kell deríteni az ULC-ből.
  - Terv: az app ne közvetlenül a MySQL-hez kapcsolódjon, hanem egy **API**-hoz az ULC-ben (ugyanazon a nyelven, mint az ULC). Az app **offline-first** marad: helyi SQLite, és szinkronizál, ha eléri a szervert. Házon kívül később VPN-nel (pl. Tailscale), portnyitás nélkül.
  - A kettler-edzo külön repó/mappa marad az ULC mellett (az Android-fordítás miatt), az ULC egy új modult kap (táblák, API, webes oldalak).
