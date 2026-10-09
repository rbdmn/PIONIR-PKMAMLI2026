"""Factory operator tool. No COM access, device writes, or firmware upload.
Creates one private header and one printable HTML label with an inline Wi-Fi QR.
Keep the generated header: factory reset must reproduce the label credentials.
"""
import argparse
import base64
import re
import secrets
import string
from pathlib import Path

def normalize_mac(value):
    compact = re.sub(r"[:-]", "", value).upper()
    if not re.fullmatch(r"[0-9A-F]{12}", compact) or compact in ("000000000000", "FFFFFFFFFFFF"):
        raise ValueError("Use the full, valid Wi-Fi Station MAC (12 hex digits).")
    if int(compact[:2], 16) & 1:
        raise ValueError("Multicast MAC cannot identify a device.")
    return compact

def make_password():
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(20))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sta-mac", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    mac = normalize_mac(args.sta_mac)
    directory = Path(args.output).resolve() / ("SP-" + mac)
    # Never replace an issued label, even accidentally.
    directory.mkdir(parents=True, exist_ok=False)
    from reportlab.graphics.barcode.qr import QrCodeWidget
    from reportlab.graphics.shapes import Drawing
    from reportlab.graphics import renderSVG
    ssid = "SmartPlug-" + mac
    ap_password, admin_password = make_password(), make_password()
    qr = QrCodeWidget(f"WIFI:T:WPA;S:{ssid};P:{ap_password};;")
    bounds = qr.getBounds()
    drawing = Drawing(bounds[2], bounds[3])
    drawing.add(qr)
    svg = renderSVG.drawToString(drawing)
    if isinstance(svg, str):
        svg = svg.encode("utf-8")
    image = base64.b64encode(svg).decode("ascii")
    header = "\n".join([
        "#pragma once",
        f'#define SMARTPLUG_FACTORY_STA_MAC "{mac}"',
        f'#define SMARTPLUG_FACTORY_AP_SSID "{ssid}"',
        f'#define SMARTPLUG_FACTORY_AP_PASSWORD "{ap_password}"',
        f'#define SMARTPLUG_FACTORY_ADMIN_PASSWORD "{admin_password}"', ""])
    (directory / "factory_profile.h").write_text(header, encoding="utf-8")
    (directory / "label.html").write_text(f'''<!doctype html><html lang="id"><meta charset="utf-8">
<title>Label SmartPlug SP-{mac}</title><style>body{{font:16px Arial;color:#000;background:#fff;max-width:650px;margin:30px auto}}img{{width:250px}}section{{border:2px solid #000;padding:20px;margin-bottom:24px;break-inside:avoid}}code{{font-size:18px}}</style>
<section><h1>SmartPlug</h1><p>ID: SP-{mac}</p><img alt="QR Wi-Fi SmartPlug" src="data:image/svg+xml;base64,{image}">
<p>Pindai untuk bergabung ke Wi-Fi perangkat.</p><p>Wi-Fi: <b>{ssid}</b></p><p>Password Wi-Fi: <code>{ap_password}</code></p>
<p>Buka <b>http://192.168.4.1</b> setelah tersambung.</p></section>
<section><h2>Kartu akses pemilik - simpan terpisah</h2><p>ID: SP-{mac}</p><p>Username: <b>admin</b></p>
<p>Password admin awal: <code>{admin_password}</code></p><p>Ganti password admin saat setup pertama. Jangan menempelkan kartu admin pada area yang dapat diakses umum.</p></section></html>''', encoding="utf-8")
    print(f"Created private factory profile and label in {directory}. No device was accessed.")

if __name__ == "__main__":
    main()
