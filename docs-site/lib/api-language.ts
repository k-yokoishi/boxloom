export const apiLanguages = [
  { value: 'python', label: 'Python' },
  { value: 'rest', label: 'REST' },
] as const;

export type ApiLanguage = (typeof apiLanguages)[number]['value'];

export const defaultApiLanguage: ApiLanguage = 'python';

export const apiLanguageStorageKey = 'boxloom.api-language';

export const apiLanguageQueryKey = 'lang';

export function isApiLanguage(value: unknown): value is ApiLanguage {
  return apiLanguages.some((language) => language.value === value);
}

/** Reads the choice the way the bootstrap script does: query string, then storage. */
export function resolveApiLanguage(): ApiLanguage {
  try {
    const fromQuery = new URLSearchParams(window.location.search).get(
      apiLanguageQueryKey,
    );
    if (isApiLanguage(fromQuery)) return fromQuery;

    const stored = window.localStorage.getItem(apiLanguageStorageKey);
    if (isApiLanguage(stored)) return stored;
  } catch {
    // Storage can be unavailable; the default is a fine answer.
  }

  return defaultApiLanguage;
}

/** The single switch every `ApiVariant` on the page reacts to, through CSS. */
export function applyApiLanguage(language: ApiLanguage) {
  document.documentElement.dataset.apiLanguage = language;

  try {
    window.localStorage.setItem(apiLanguageStorageKey, language);
  } catch {
    // Storage can be unavailable; the choice just will not be remembered.
  }
}

const values = JSON.stringify(apiLanguages.map((language) => language.value));
const storageKey = JSON.stringify(apiLanguageStorageKey);
const queryKey = JSON.stringify(apiLanguageQueryKey);
const fallback = JSON.stringify(defaultApiLanguage);

/**
 * Runs while the browser parses `<head>`, so a statically exported page shows
 * the stored interface on the first paint instead of flashing the default.
 */
export const apiLanguageBootstrap = `(function () {
  var values = ${values};
  var next = ${fallback};
  try {
    var fromQuery = new URLSearchParams(location.search).get(${queryKey});
    if (values.indexOf(fromQuery) !== -1) {
      next = fromQuery;
    } else {
      var stored = localStorage.getItem(${storageKey});
      if (values.indexOf(stored) !== -1) next = stored;
    }
  } catch (error) {}
  document.documentElement.dataset.apiLanguage = next;
  try {
    localStorage.setItem(${storageKey}, next);
  } catch (error) {}
})();`;
