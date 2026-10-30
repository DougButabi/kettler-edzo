# A hangos visszajelzés mondatai, és a hangfájlok legenerálása (Microsoft Edge felolvasó, edge-tts).
# Futtatás: python tools/hangok.py  ->  app/src/main/res/raw/v_<hang>_<kulcs>_<sorszám>.mp3
# A hang: m = férfi (hu-HU-TamasNeural), f = női (hu-HU-NoemiNeural). A kulcsokat a Voice.kt használja.
import asyncio
import os

import edge_tts

PHRASES = {
    "q1": [  # 1/4
        "Megvan az első negyed, csak így tovább!",
        "Egynegyed kész, jól kezdtél!",
        "Túl vagy az első negyeden!",
        "Negyedénél tartasz, szép tempó!",
        "Az első negyed megvan, hajrá!",
        "Egy negyed letudva, folytasd!",
        "Jó úton jársz, megvan a negyede!",
        "Negyed kész, maradj ebben a ritmusban!",
        "Az első negyed pipa!",
        "Szép kezdés, egynegyednél vagy!",
    ],
    "t1": [  # 1/3
        "Egyharmadnál tartasz, jól haladsz!",
        "Megvan az első harmad!",
        "Egyharmad kész, csak így tovább!",
        "Túl vagy az első harmadon!",
        "Az első harmad letudva, hajrá!",
        "Harmadánál jársz, jó a tempó!",
        "Egyharmad pipa, folytasd!",
        "Szépen haladsz, megvan az egyharmad!",
        "Az első harmad kész, tartsd a lendületet!",
        "Egyharmadon túl, jól megy ez!",
    ],
    "h": [  # 1/2
        "Félúton vagy, tartsd a tempót!",
        "Megvan a fele!",
        "A felénél jársz, szuper!",
        "Félidő! Csak így tovább!",
        "A fele kész, a nehezén túl vagy!",
        "Félúton tartasz, jól megy!",
        "Az út fele megvan, hajrá!",
        "Fele kész, maradj a ritmusban!",
        "Félútnál vagy, szép munka eddig!",
        "A felén túl, innen már lejtő!",
    ],
    "t2": [  # 2/3
        "Kétharmadon túl vagy, már látszik a vége!",
        "Megvan a kétharmada!",
        "Kétharmad kész, már csak egy harmad!",
        "Túl vagy a kétharmadon, hajrá!",
        "Kétharmadnál jársz, bírod még!",
        "Már csak az utolsó harmad van hátra!",
        "Kétharmad pipa, folytasd!",
        "Szépen haladsz, kétharmadnál tartasz!",
        "A kétharmada megvan, nem állunk meg!",
        "Az utolsó harmad jön, gyerünk!",
    ],
    "q3": [  # 3/4
        "Háromnegyeden túl vagy, mindjárt kész!",
        "Megvan a háromnegyede!",
        "Már csak egy negyed van hátra!",
        "Háromnegyednél jársz, hajrá!",
        "Az utolsó negyed jön, gyerünk!",
        "Háromnegyed kész, bírd még egy kicsit!",
        "Túl vagy a háromnegyeden, nem sok van hátra!",
        "Háromnegyed pipa, jön a célegyenes!",
        "Szép munka, már csak az utolsó negyed!",
        "Háromnegyeden túl, tartsd ki!",
    ],
    "p95": [  # 95%
        "Mindjárt célba érsz, hajrá!",
        "Már csak egy hajrá van hátra!",
        "Már a célegyenesben vagy!",
        "Szinte kész, add bele az utolsót!",
        "Célegyenes, gyerünk!",
        "Már csak egy egészen kicsi van hátra!",
        "Mindjárt megvan, ne add fel most!",
        "Az utolsó pillanatok, hajrá!",
        "Ott vagy már, még egy kicsit!",
        "Utolsó erőbedobás!",
    ],
    "goal": [
        "Megvan a cél, szép munka!",
        "Cél teljesítve, gratulálok!",
        "Kész vagy, ügyes voltál!",
        "Ez az! Megvan a cél!",
        "Szép teljesítmény, a cél megvan!",
        "Célba értél, büszke lehetsz magadra!",
        "Megcsináltad, gratulálok!",
        "Cél pipa! Szép edzés volt!",
        "Kész! Ma is megérte!",
        "Teljesítve! Jár egy kis pihenő!",
    ],
    "km": [
        "Már csak egy kilométer!",
        "Az utolsó kilométer jön!",
        "Még egy kilométer, és kész!",
    ],
    "min": [
        "Már csak egy perc!",
        "Az utolsó perc, hajrá!",
        "Még egy perc, és kész!",
    ],
}

VOICES = {"m": "hu-HU-TamasNeural", "f": "hu-HU-NoemiNeural"}
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


async def main():
    os.makedirs(OUT, exist_ok=True)
    for v, voice in VOICES.items():
        for key, texts in PHRASES.items():
            for i, text in enumerate(texts, 1):
                path = os.path.join(OUT, f"v_{v}_{key}_{i}.mp3")
                if os.path.exists(path):
                    continue
                await edge_tts.Communicate(text, voice).save(path)
    print(len(os.listdir(OUT)), "fájl")


asyncio.run(main())
