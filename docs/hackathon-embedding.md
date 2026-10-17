# Embedded authoring and task components

`npm run build:authoring` and `npm run build:tasks` produce independent ES-module/CSS distributions in `frontend/dist-embed/authoring` and `frontend/dist-embed/tasks`. Each can be rebuilt and deployed separately against the v1 application API. Dependencies are bundled; there is no runtime CDN or separately administered backend.

```javascript
import { mount } from '/components/authoring/orqix-authoring.js'

const component = mount(document.getElementById('workflow'), {
  apiVersion: 'v1',
  workspaceId: '00000000-0000-7000-8000-000000000201',
  // Optional for authoring: open this draft instead of the catalog.
  // workflowId: '<workflow-uuid>',
  onNavigate: path => console.info('Component navigation', path),
})

// The host may navigate within the selected workspace, then cleanly remove the component.
// component.navigate('/w/<workspace-uuid>/workflows')
// component.unmount()
```

Load that artifact's `orqix.css` and apply the normal `data-brand="wf"` or `data-brand="orqix"` document theme. Styles include the application reset; use a dedicated host surface or an iframe when the surrounding application's styles require isolation. Dialog portals mount in the current document. The minimal sample host is deliberately a dedicated surface.

Host inputs are workspace UUID, optional workflow UUID and API major version. An incompatible major version or malformed UUID is refused before mounting. Memory routing keeps the host URL unchanged. The navigation callback and bubbling `orqix:navigate` event carry `{ path, workspaceId }`; navigation is not an authorization grant. Task links to request detail work inside the task component. Destinations outside the component's documented routes show a visible unavailable message, allowing the host to provide its own destination through the callback.

Identity, permissions and groups come from the existing authenticated application session through `/api/v1/me`; host parameters cannot assert a user or membership. Serve `/api/v1`, `/oauth2`, `/login` and `/logout` on the same origin through the application's approved proxy. The existing session/CSRF policy applies. Configure the corresponding OIDC redirect origin as with the main application; no token, secret or role is passed in the mount options.

`npm run build:embedded` packages both components and the sample host into the main frontend distribution. The Docker application includes these files. After signing in, open:

```text
http://localhost:8080/embed-host/index.html?component=authoring&workspace=00000000-0000-7000-8000-000000000201
http://localhost:8080/embed-host/index.html?component=tasks&workspace=00000000-0000-7000-8000-000000000201
```

Change the port to match the local stack. Independently deployed artifacts must retain relative dynamic chunks/fonts and their CSS. Host-specific Orchesstra packaging/version compatibility remains an external qualification gate. A successful component build does not close that gate.
