// Add the server-provided CSRF token to same-origin writes in cloud mode.
(() => {
 const originalFetch = window.fetch.bind(window);
 window.fetch = async (input, options = {}) => {
  const url = new URL(typeof input === 'string' ? input : input.url, location.href);
  const method = (options.method || (input instanceof Request ? input.method : 'GET')).toUpperCase();
  if (url.origin !== location.origin || ['GET','HEAD','OPTIONS'].includes(method)) return originalFetch(input,options);
  const response = await originalFetch('/api/session/csrf', {cache:'no-store'});
  if (!response.ok) throw new Error('Session unavailable. Reload and sign in again.');
  const csrf = await response.json();
  const headers = new Headers(options.headers || (input instanceof Request ? input.headers : undefined));
  if (csrf.enabled) headers.set(csrf.header,csrf.token);
  return originalFetch(input,{...options,headers});
 };
})();
