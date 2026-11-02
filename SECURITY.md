# Security policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's
**private vulnerability reporting** on this repository (Security → Report a
vulnerability), or contact the maintainer privately through the address listed
on the repository profile.

Include:

- a description of the issue and its impact,
- the app version and Android version,
- steps to reproduce, if possible,
- any relevant logs with tokens, IP addresses and personal data removed.

## Scope

Security-relevant areas include:

- storage and transmission of the Signal K token,
- the local tile HTTP server,
- cleartext HTTP/WebSocket connections to Signal K and NMEA gateways,
- parsing of untrusted chart files (S-57, MBTiles, PMTiles, GeoPackage, XYZ)
  and NMEA/AIS input.

## Not in scope

- Vulnerabilities in third-party dependencies that are already fixed upstream;
  report them to the upstream project as well.
- Issues that require a rooted device or physical access with unlocked
  bootloader.
- The accuracy of chart, AIS or sensor data (see the safety notice in the
  README).

## Safety

Derrotero is not a certified navigation instrument. Do not use it as the
primary means of navigation, and always keep official charts and manual
control of the vessel available.
