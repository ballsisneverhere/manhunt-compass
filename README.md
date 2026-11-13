# ManhuntCompass

Spigot / Paper plugin (1.16+ servers, Java 17+).

## Commands (OP / manhunt.admin)
- /speedrunner <player>  - set the runner
- /hunter <player>       - make a hunter + give them the tracking compass
- /manhunt status | reset | compass

## Build
Option A (Maven):   mvn package   ->  target/ManhuntCompass.jar
Option B (no install): upload this folder to a GitHub repo, open the Actions tab,
        run "Build plugin", download the ManhuntCompass artifact (the .jar).
Then drop the .jar into your server's /plugins folder and restart.
