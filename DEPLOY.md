# Backend en Railway

Guía completa: `docs/deployment.md` en el repositorio del frontend (`Cafeteria`): Gmail, Vercel, copias de seguridad y riesgos.

## Archivos
- `Dockerfile`: compila con Maven y ejecuta con Java 21 como usuario sin privilegios (`SPRING_PROFILES_ACTIVE=production`).
- `railway.json`: construye con el Dockerfile, healthcheck en `/actuator/health`, reinicio ante fallos.
- `src/main/resources/application-production.properties`: perfil autosuficiente, todo por variables de entorno. El `Dockerfile` fija `SPRING_CONFIG_LOCATION=classpath:/application-production.properties`, así que en producción **no se lee `application.properties`**.
- `.env` (local, no se sube a GitHub) guarda las claves y contraseñas; `.env.example` es la plantilla. `application.properties` las lee con `spring.config.import`.

## Variables en Railway

| Variable | Obligatoria | Ejemplo |
|---|---|---|
| `DB_URL` | sí | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` (una sola variable, formato JDBC) |
| `DB_USER` `DB_PASSWORD` | sí | `${{Postgres.PGUSER}}` y `${{Postgres.PGPASSWORD}}` |
| `CLOUDINARY_CLOUD_NAME` `CLOUDINARY_API_KEY` `CLOUDINARY_API_SECRET` | sí | credenciales nuevas |
| `APP_PUBLIC_BASE_URL` | sí | `https://tu-app.vercel.app` |
| `MAIL_FROM` `MAIL_USERNAME` `MAIL_PASSWORD` | sí (por defecto se envía por SMTP, plan Pro de Railway) | tu Gmail, tu Gmail y su contraseña de aplicación de 16 letras |
| `GMAIL_CLIENT_ID` `GMAIL_CLIENT_SECRET` `GMAIL_REFRESH_TOKEN` | solo con `MAIL_SENDER=gmail-api` (planes de Railway sin SMTP) | ver `docs/deployment.md` |
| `REGISTRATION_ENABLED` | no (por defecto `true`) | `false` cierra el registro del todo; el registro exige un código de activación |
| `SUPERADMIN_EMAIL` `SUPERADMIN_PASSWORD` | no (sin ellas no existe el panel de superadmin) | tu Gmail de empresa; contraseña de 8+ caracteres |
| `MAIL_SENDER` | no (por defecto `smtp`) | `smtp` (solo plan Pro de Railway) o `gmail-api` (cualquier plan) |
| `DB_POOL_SIZE` | no | `10` |
| `DEMO_SITE_ENABLED` | no (por defecto `true`) | `false` para no crear el sitio demo `/la-terraza` |

## Comprobar en local el perfil de producción
```bash
./mvnw -DskipTests package
SPRING_PROFILES_ACTIVE=production SPRING_CONFIG_LOCATION=classpath:/application-production.properties PORT=8089 \
DB_URL='jdbc:postgresql://localhost:5432/cafeteria?currentSchema=prod_sim' DB_USER=postgres DB_PASSWORD=CLAVE \
SPRING_FLYWAY_SCHEMAS=prod_sim CLOUDINARY_CLOUD_NAME=x CLOUDINARY_API_KEY=x CLOUDINARY_API_SECRET=x \
APP_PUBLIC_BASE_URL=http://localhost:4200 MAIL_FROM=tu@gmail.com GMAIL_CLIENT_ID=x GMAIL_CLIENT_SECRET=x GMAIL_REFRESH_TOKEN=x \
java -jar target/cafeteria-0.0.1-SNAPSHOT.jar
```

## Tests
`./mvnw test` usa tu `application.properties` local y un esquema aparte (`test_it`) de tu base de desarrollo.

## Cuentas adicionales de superadmin (V8)

La cuenta principal sigue definiéndose con `SUPERADMIN_EMAIL` y `SUPERADMIN_PASSWORD` y no cambia. Una segunda cuenta se crea **al arrancar** si están definidas estas variables. **La configuración manda:** en cada arranque el rol, las regiones y el estado de la cuenta se sincronizan con las variables, pero **nunca se pisa una contraseña** que la persona ya haya cambiado.

| Variable | Valor |
|---|---|
| `SUPERADMIN_OPERATOR_EMAIL` | Correo de la persona. Recibe su propio código de 6 dígitos al iniciar sesión. |
| `SUPERADMIN_OPERATOR_PASSWORD` | Contraseña **temporal** (mínimo 8 caracteres). Debe cambiarla en el primer ingreso. |
| `SUPERADMIN_OPERATOR_ROLE` | `OPERATOR` (por defecto, solo lectura de negocios y actividad) o `ADMIN` (todos los permisos). |
| `SUPERADMIN_OPERATOR_REGIONS` | Regiones de cobro que atiende, por ejemplo `AR`. |
| `SUPERADMIN_OPERATOR_ENABLED` | `false` **deshabilita la cuenta** (mantén el correo definido). Sus sesiones abiertas se cierran en la siguiente petición. |

**Revocar el acceso:** pon `SUPERADMIN_OPERATOR_ENABLED=false` con el correo definido, o cambia el rol. Si quitas todas las variables, la cuenta **queda como estaba** (así se puede retirar la contraseña temporal del entorno tras el primer ingreso); para revocarla en ese caso, usa la variable `ENABLED=false` o desactívala en la base de datos (`superadmin_accounts.enabled`).

Al cambiar la contraseña desde el panel se rota la sesión y se cierran las demás sesiones de esa cuenta.
