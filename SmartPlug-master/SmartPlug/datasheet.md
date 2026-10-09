# Dokumentasi SmartPlug

Pilih firmware dan dokumen sesuai jalur integrasi yang digunakan.

## REST API

- [REST Product Datasheet R1.0](output/pdf/SmartPlug-REST-Product-Datasheet-R1.0.pdf)
- [REST Integration Guide R1.0](output/pdf/SmartPlug-REST-Integration-Guide-R1.0.pdf)
- Build profile: `esp07_rest` | Firmware: `smartplug-bringup 0.6.0-rest`

## MQTT

- [MQTT Product Datasheet R1.0](output/pdf/SmartPlug-MQTT-Product-Datasheet-R1.0.pdf)
- [MQTT Integration Guide R1.0](output/pdf/SmartPlug-MQTT-Integration-Guide-R1.0.pdf)
- Build profile: `esp07_mqtt` | Firmware: `smartplug-bringup 0.6.0-mqtt`

REST memakai dashboard dan REST API v1 sebagai jalur integrasi utama. MQTT
menambahkan telemetry dan kontrol melalui broker, sementara dashboard lokal
tetap digunakan untuk Wi-Fi dan commissioning.

Kontrak endpoint REST ada pada [LOCAL-API.md](firmware/LOCAL-API.md); kontrak
topic MQTT ada pada [MQTT.md](firmware/MQTT.md).
