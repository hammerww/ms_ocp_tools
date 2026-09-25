# Certificados corporativos para la construcción

Colocar aquí únicamente los certificados públicos de la CA raíz y, si aplica,
de la CA intermedia corporativa que firma la conexión HTTPS inspeccionada.

- Exportarlos en formato X.509 codificado en Base-64 (PEM).
- Usar la extensión `.crt`.
- No exportar el certificado hoja del sitio, por ejemplo
  `CN=repo1.maven.org`; se deben seleccionar los certificados corporativos que
  aparecen por encima de él en la ruta de certificación.
- No copiar certificados personales, archivos PFX/P12 ni claves privadas.
- Los archivos `.crt` y `.cer` de esta carpeta están excluidos de Git.

El `Dockerfile` incorpora los `.crt` al almacén de confianza del sistema de la
imagen de construcción antes de que Maven acceda a los repositorios HTTPS. No
se copian a la imagen final de la aplicación.
