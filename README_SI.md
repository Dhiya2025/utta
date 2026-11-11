# Mini Walkie Talkie — Cloudflare Workers deploy

මෙය Android Push-to-Talk සහ room text messages සඳහා client/server prototype එකකි. `cloudflare/src/` තුළ Cloudflare Workers + Durable Objects relay එක ඇත. `wrangler.toml` එක project root එකේ තිබේ. `server/` සහ `render.yaml` Render සඳහා වූ විකල්පය ලෙස තවම ඇතුළත්; Cloudflare deploy එකට ඒවා අවශ්‍ය නැත.

## Cloudflare free deploy කිරීම

Cloudflare Workers Free plan එක `workers.dev` subdomain එකකට deploy කිරීමට custom domain එකක් අවශ්‍ය නොවේ. Cloudflare product page එක free signup සඳහා card අවශ්‍ය නොවන බව ප්‍රචාරය කරයි. Signup verification account එක අනුව වෙනස් විය හැකි බැවින්, Cloudflare ඔබෙන් card එකක් ඉල්ලුවොත් කිසිදු paid plan එකකට upgrade නොකරන්න.

1. Project ZIP එක extract කර, `wrangler.toml` සහ `cloudflare/` folder එක **root එකේම** තියෙන බව බලන්න. Android Studio තුළින් නොව, command line/Terminal භාවිතා කරන්න.
2. Computer එකේ Node.js 18 හෝ නවතම එක ස්ථාපිත කර තිබිය යුතුයි. Terminal එකෙන් `MiniWalkieTalkie` project root folder එකට යන්න.
3. `npx wrangler login` ධාවනය කර browser එකෙන් Cloudflare account එකට sign in/authorize කරන්න. Wrangler CLI එක අවශ්‍ය නම් download කරයි.
4. `npx wrangler deploy` ධාවනය කරන්න. මෙය Worker සහ room සඳහා අවශ්‍ය SQLite Durable Object migration එක deploy කරයි.
5. Deploy අවසානයේ පෙන්වන `https://mini-walkie-talkie-relay.<your-account-subdomain>.workers.dev` URL එක පිටපත් කරන්න. එය app එකේ **server address** field එකට paste කරන්න; `/health` path එක එකතු නොකරන්න.
6. App එක `https://` ලිපිනය `wss://` බවට හරවා, තෝරාගත් room එක connection URL එකට ස්වයංක්‍රීයව එකතු කරයි. ඔබේ නම සහ room එක තෝරා **CONNECT TO ROOM** ඔබන්න. Room එකේ අනෙක් අයත් එම URL එක සහ room name එක භාවිතා කළ යුතුය.

`https://<worker-url>/health` විවෘත කළ විට JSON health response එකක් ලැබේ. Cloudflare එකේ [workers.dev URL](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/) වෙත custom domain එකක් නැතිව deploy කළ හැක.

## Free plan සීමා

Cloudflare Free Durable Objects වල දිනකට 100,000 request-equivalent සීමාවක් සහ 13,000 GB-s compute-duration quota එකක් ඇත. Incoming WebSocket messages 20:1 ලෙස request-equivalent වලට ගණන් කරන අතර outgoing WebSocket message සඳහා අමතර ගාස්තුවක් නැත. Free quota ඉක්මවූ විට වැඩි usage එක charge නොවී, ඒ වර්ගයේ operations දිනපතා quota reset වන තෙක් fail විය හැක. Voice audio එක නිතර යැවීම quota එක ඉක්මවිය හැකි බැවින් මෙය කුඩා hobby/testing භාවිතයට පමණක් සලකන්න. [Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/) සහ [Durable Objects WebSocket example](https://developers.cloudflare.com/durable-objects/examples/websocket-hibernation-server/) බලන්න.

## APK build කිරීම

Android Studio වල project folder එක open කර sync කරන්න. Debug APK එක `app/build/outputs/apk/debug/app-debug.apk` තුළ ලැබේ. Command line එකෙන් `./gradlew assembleDebug` ධාවනය කළ හැක. Android SDK සහ JDK අවශ්‍ය වේ.

## වැදගත් ආරක්ෂක සටහන

මෙය මුල් prototype එකකි. Room password, authentication හෝ user verification නැත. URL එක දන්නා ඕනෑම අයෙකුට room name එක අනුමාන/දැනගත්තොත් join විය හැක. Cloudflare `wss://` transport එක TLS හරහා ආරක්ෂා කළත්, app-level voice encryption හෝ end-to-end encryption මෙහි නැත. සංවේදී සංවාද සඳහා භාවිතා නොකරන්න. Voice audio එක 16 kHz mono PCM බැවින් WebRTC/Opus වැනි compression එකකට වඩා data වැඩියෙන් භාවිතා විය හැක.

## Render විකල්පය

Render deploy කිරීමට `render.yaml` සහ `server/` folder එක භාවිතා කළ හැක. Render free service එක idle විනාඩි 15කට පසු spin down විය හැකි අතර card verification ඉල්ලන්නේ නම් Cloudflare path එක භාවිතා කරන්න. [Render free limits](https://render.com/docs/free) බලන්න.
