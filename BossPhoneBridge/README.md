# Boss Phone Bridge

Android-app der bruger telefonens eget SIM til SMS og opkald. Ingen ekstern SMS-/telefoniudbyder er nødvendig; almindelige operatørpriser kan stadig gælde.

## Funktioner
- Send SMS, inkl. lange multipart-SMS'er.
- Vis status når beskeden er afleveret til mobilnettet og, hvor operatøren understøtter det, leveringskvittering.
- Foretag almindelige SIM-opkald.
- Valider telefonnumre og kræv eksplicit bekræftelse før SMS/opkald.
- Kan forudfyldes fra andre apps via `bossphone://compose?phone=...&message=...`; brugeren skal stadig trykke Send/Ring.

## Byg/installér
Åbn projektmappen i Android Studio (nyere version med Android SDK 35), lad Gradle synkronisere, og vælg **Build > Build APK(s)**. Installer APK'en på Android-telefonen og tillad SMS/opkald, når Android spørger.

## ChatGPT-integration
En lokal Android-app kan ikke modtage en kommando direkte fra en cloud-chat uden en transportkanal. Den sikre gratis basis er derfor deep-link/intent-forudfyldning med bekræftelse. Fuld fjernstyring kræver en særskilt connector/server eller en anden transportkanal og bør autentificeres; appen accepterer ikke skjulte, ubekræftede send/ring-kommandoer.
