# Política de Privacidad / Privacy Policy — OneProxyClick

**Última actualización / Last updated:** Noviembre 2026  
**Paquete / Package ID:** `com.oneproxyclick.app`  
**Desarrollador / Developer:** Edgar Augusto Sanchez Villanueva  
**Contacto / Contact:** kevinmitnick1313@gmail.com

---

## Español

**OneProxyClick** es una herramienta técnica de depuración y análisis local de tráfico HTTP/HTTPS para Android, diseñada para desarrolladores, ingenieros de QA y auditores de seguridad.

### 1. Uso de Android `VpnService` y procesamiento 100 % local
* **Interfaz de red local (Loopback):** OneProxyClick utiliza la API `VpnService` de Android únicamente para crear una interfaz VPN local en el propio dispositivo (`127.0.0.1`) que permite capturar, inspeccionar y reenviar peticiones HTTP/HTTPS iniciadas por el usuario.
* **Sin servidores externos:** Ningún paquete de red, encabezado, certificado ni carga útil (*payload*) se transmite a servidores propios, servicios en la nube ni terceros.
* **Sin compartición ni venta de datos:** Los datos procesados a través de `VpnService` nunca se comparten, venden ni utilizan con fines publicitarios o de perfilado.

### 2. Recopilación de datos personales
* **Cero recopilación de datos:** OneProxyClick no requiere creación de cuentas ni recopila nombres, correos electrónicos, ubicación geográfica, contactos, listas de aplicaciones instaladas ni identificadores de publicidad (AAID).
* **Sin analíticas ni rastreadores:** La aplicación no integra SDKs de publicidad ni telemetría de terceros.

### 3. Almacenamiento, retención y eliminación de datos
* **Control total del usuario:** Los registros de tráfico en memoria, los certificados raíz locales (CA) y los archivos exportados manualmente o mediante Auto-JSON (`.json` / `.jsonl`) se guardan exclusivamente en el almacenamiento local del dispositivo.
* **Eliminación:** Puedes borrar las capturas en cualquier momento desde el botón de limpieza dentro de la app, eliminando los archivos en tu carpeta de descargas o desinstalando la aplicación.

### 4. Permisos del dispositivo
* **`BIND_VPN_SERVICE`:** Necesario para enrutar localmente el tráfico HTTP/HTTPS hacia el motor de inspección del dispositivo.
* **`SYSTEM_ALERT_WINDOW` (Burbuja flotante):** Opcional, utilizado únicamente para mostrar el botón flotante de encendido/apagado rápido sobre otras aplicaciones.
* **Notificaciones (`POST_NOTIFICATIONS`):** Utilizadas para mostrar el estado activo del servicio en primer plano mientras el proxy está encendido.

### 5. Compras dentro de la aplicación (Google Play Billing)
* La actualización vitalicia a **OneProxyClick Pro** se procesa íntegramente mediante el sistema oficial de **Google Play Billing**. OneProxyClick no tiene acceso a tarjetas bancarias ni datos financieros; únicamente recibe de Google Play un token criptográfico de confirmación para desbloquear las funciones Pro.

### 6. Cambios y contacto
Cualquier actualización a esta Política de Privacidad se publicará en esta misma página. Si tienes dudas sobre privacidad o seguridad, escribe a: **kevinmitnick1313@gmail.com**.

---

## English

**OneProxyClick** is a local HTTP/HTTPS traffic debugging and network analysis utility for Android, built for developers, QA engineers, and authorized security researchers.

### 1. Use of Android `VpnService` & 100% Local Processing
* **Local Loopback Interface:** OneProxyClick uses Android's `VpnService` API strictly to establish a local on-device VPN loopback interface (`127.0.0.1`) to capture, inspect, and replay HTTP/HTTPS requests initiated on the device.
* **No Remote Servers:** No network traffic, headers, certificates, or payloads are ever routed to external VPN servers, cloud infrastructure, or third parties.
* **No Data Sharing or Monetization:** Data inspected through `VpnService` is never shared with third parties, sold, or used for advertising or profiling purposes.

### 2. Personal Data Collection
* **Zero Personal Data Collected:** OneProxyClick does not require account registration and does not collect names, email addresses, location data, contacts, installed app inventories, or advertising identifiers (AAID).
* **No Third-Party Trackers:** The application contains no ad networks or third-party analytics SDKs.

### 3. Data Storage, Retention & Deletion
* **Full User Control:** In-memory session logs, local root CA certificates, and user-exported capture files (`.json` / `.jsonl`) reside strictly on your device's local storage.
* **Data Deletion:** You can permanently delete all captured data at any time using the in-app clear button, deleting exported files from your storage, or uninstalling the application.

### 4. Device Permissions
* **`BIND_VPN_SERVICE`:** Required to route device HTTP/HTTPS traffic locally to the on-device inspection proxy.
* **`SYSTEM_ALERT_WINDOW` (Floating Bubble):** Optional permission used solely to display a quick-toggle floating button over other apps.
* **Notifications (`POST_NOTIFICATIONS`):** Used to display the active foreground service notification while the local proxy is running.

### 5. In-App Purchases (Google Play Billing)
* The one-time lifetime upgrade to **OneProxyClick Pro** is processed exclusively by **Google Play Billing**. OneProxyClick never accesses credit card or billing details; the app only receives a purchase confirmation token from Google Play to unlock Pro features.

### 6. Updates & Contact
Any updates to this Privacy Policy will be posted on this page. For privacy or security inquiries, contact: **tu-correo@ejemplo.com**.
