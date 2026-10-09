/** Authentication is delegated to the IdP through Spring Security (docs/build/S6-security.md section 1). */

export const LOGIN_PATH = '/oauth2/authorization/orqix'

/** Read a cookie by name (the XSRF-TOKEN cookie is intentionally not HttpOnly). */
export function readCookie(name: string, cookieString: string = document.cookie): string | null {
  for (const part of cookieString.split(';')) {
    const index = part.indexOf('=')
    if (index < 0) continue
    if (part.slice(0, index).trim() === name) {
      try {
        return decodeURIComponent(part.slice(index + 1).trim())
      } catch {
        return part.slice(index + 1).trim()
      }
    }
  }
  return null
}

let redirecting = false

/**
 * 401 handling: full-page navigation to the OIDC entry point. The server keeps the saved request,
 * so the user returns to where they were. Guarded so a burst of 401s starts only one navigation.
 */
export function redirectToLogin(): void {
  if (redirecting) return
  redirecting = true
  window.location.assign(LOGIN_PATH)
}

/** Reset the guard (tests only). */
export function resetLoginRedirectGuard(): void {
  redirecting = false
}

let signOutOverride: (() => void) | null = null

/** Mock mode has no server to log out of; it installs its own handler. Never used against a real backend. */
export function setSignOutHandler(handler: (() => void) | null): void {
  signOutOverride = handler
}

/**
 * Sign out with a real form POST so the browser follows the RP-initiated logout redirect chain
 * (S6 section 1: POST /logout with CSRF, then the IdP end_session_endpoint).
 */
export function signOut(): void {
  if (signOutOverride) {
    signOutOverride()
    return
  }
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = '/logout'
  const token = readCookie('XSRF-TOKEN')
  if (token) {
    const input = document.createElement('input')
    input.type = 'hidden'
    input.name = '_csrf'
    input.value = token
    form.appendChild(input)
  }
  document.body.appendChild(form)
  form.submit()
}
