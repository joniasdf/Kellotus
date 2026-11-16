# Kellotus

Android-sovellus, joka näyttää kellon kahdesta lähteestä rinnakkain: valitulta NTP-palvelimelta ja GPS-satelliiteista. Molemmat ajat ankkuroidaan `elapsedRealtimeNanos`-kelloon, joten puhelimen kellon siirto ei siirrä näytettyä aikaa.

Käyttöliittymä on suomeksi. Sovellus kysyy tarkan sijaintiluvan vain GPS-aikaa varten eikä tallenna sijaintia.

## Käännös

Tarvitset JDK 17:n ja Android SDK:n (compileSdk 37.2, build-tools 37).

```bash
./gradlew test assembleDebug
```

Debug-APK:

```text
app/build/outputs/apk/debug/Kellotus-debug.apk
```

Valmis debug-APK on GitHub Releasesissa: [github.com/joniasdf/Kellotus/releases/latest](https://github.com/joniasdf/Kellotus/releases/latest). Sen voi ladata ja asentaa puhelimeen ilman omaa käännöstä.

Asennus ladatusta paketista tai paikallisesta käännöksestä:

```bash
adb install -r Kellotus-debug.apk
# tai
adb install -r app/build/outputs/apk/debug/Kellotus-debug.apk
```

`minSdk` on 26. Uusin vakaa alusta on Android SDK Platform 37.2 (`compileSdk` 37, minor 2). `targetSdk` on 37, joka on uusin vakaana asetettava kohde-API.

## NTP-palvelimet

Listalla ovat nimet, jotka resolvoituivat 16.11.2026:

| Palvelin | Huomio |
| --- | --- |
| time2.mikes.fi | VTT MIKES, oletus. Stratum 2, vastasi. |
| time1.mikes.fi | VTT MIKES, vastasi. |
| time.mikes.fi | VTT MIKES, vastasi. |
| ntp1.aalto.fi, ntp2.aalto.fi | Aalto, vastasivat. |
| ntp.funet.fi | Nimi on olemassa. Tästä verkosta UDP/123 ei vastannut; Funet voi rajata palvelun. |
| fi.pool.ntp.org, europe.pool.ntp.org | NTP Pool, vastasivat. |
| time.cloudflare.com, time.google.com | Vastasivat. Google oli stratum 1. |

Mukautetun nimen voi kirjoittaa itse. Android 17:ssä paikallisverkkoon (esim. 192.168.x.x) lähtevä UDP tarvitsee paikallisverkon luvan.

Sovellus tekee kahdeksan SNTP-kyselyä UDP-porttiin 123, laskee siirron ja kiertoviiveen kaavalla

```text
siirto = ((T2 − T1) + (T3 − T4)) / 2
viive  = (T4 − T1) − (T3 − T2)
```

ja pitää näytteen, jonka kiertoviive on pienin. T4 − T1 mitataan monotonisella kellolla.

## GPS

Ensisijaisesti käytetään `GnssClock`-kelloa:

```text
GPS-aika = timeNanos − (fullBiasNanos + biasNanos)
UTC      = GPS-aika − karkaussekunnit
```

Karkaussekunti luetaan vastaanottimelta, jos se on välillä 1…40. Muuten käytetään 18 sekuntia. IERS Bulletin C 72 (6.7.2026) vahvistaa, ettei karkaussekuntia tule joulukuussa 2026, joten GPS on yhä 18 sekuntia UTC:tä edellä. Aika ankkuroidaan kellon omaan `elapsedRealtimeNanos`-arvoon (API 29+). Vanhemmilla laitteilla ankkuri on takaisinkutsun saapumishetki.

Jos raakamittauksia ei tule, käytetään `Location.getTime()`- ja `Location.getElapsedRealtimeNanos()`-arvoja GPS-providerilta.

## Tarkkuuden rajat

- NTP olettaa, että meno- ja paluuyhteys ovat yhtä hitaita. Virhe on enintään noin puolet kiertoviiveestä. Pienin viive kahdeksasta näytteestä rajaa tätä, mutta mobiiliverkko voi silti olla epäsymmetrinen.
- Julkisia palvelimia ei pidä kysellä tauotta. Automaattinen synkronointi on oletuksena 15 minuutin välein ja vain sovelluksen ollessa auki.
- Puhelimen kideoskillaattori vaeltaa synkronointien välillä. 15 minuutissa tyypillinen virhe on alle millisekunnin luokkaa, mutta ei sitä parempi.
- GNSS-raakamittaukset puuttuvat osalta laitteista. Sijainnin aikaleima on millisekunnin tarkkuudella, ja jotkin valmistajat täyttävät sen laitteen omalla kellolla.
- Satelliittiaika vaatii taivaan näkyviin. Sisällä korjausta ei välttämättä tule.
- Näytön millisekunnit juoksevat ankkurista eteenpäin. Ne eivät ole uusi mittaus joka kuvassa.

## Testit

Yksikkötestit kattavat NTP-paketin, siirron ja viiveen sekä GPS-ajan muunnoksen UTC:ksi. Ne eivät tarvitse verkkoa eivätkä satelliitteja.

```bash
./gradlew test
```
