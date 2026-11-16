## 0.1.0

- First release: `init`, `screen`, `page_leave` with time on screen, `identify`, order events in `Repediu.order`, deep link campaigns, banner and promotion events, `setWriteKey` for multi-store apps, offline queue with retry, and opt-out.
- Calls made before `init` are kept, up to 100, and sent once it runs.
- The `identify` event, the only one carrying the raw phone, e-mail and name, is never written to the offline queue on disk, and is sent as soon as it is tracked.
- `init` refuses a non-`https` endpoint (except `localhost`/`10.0.2.2` for local development).
