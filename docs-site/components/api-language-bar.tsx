'use client';

import { useEffect, useLayoutEffect, useState } from 'react';
import {
  applyApiLanguage,
  apiLanguageQueryKey,
  apiLanguages,
  defaultApiLanguage,
  resolveApiLanguage,
  type ApiLanguage,
} from '@/lib/api-language';

const anchorSelector = 'h2[id], h3[id]';

/** Headings above this point in the viewport count as already scrolled past. */
const anchorOffset = 96;

const useBeforePaint = typeof window === 'undefined' ? useEffect : useLayoutEffect;

/** The heading the reader is looking at, used to hold the scroll position steady. */
function findAnchor(): HTMLElement | null {
  const headings = Array.from(
    document.querySelectorAll<HTMLElement>(anchorSelector),
  );

  let anchor: HTMLElement | null = null;
  for (const heading of headings) {
    if (heading.getBoundingClientRect().top > anchorOffset) break;
    anchor = heading;
  }

  return anchor ?? headings[0] ?? null;
}

export function ApiLanguageBar() {
  const [language, setLanguage] = useState<ApiLanguage>(defaultApiLanguage);

  useBeforePaint(() => {
    // Strict Mode remounts in development reset attributes on <html>, which drops
    // what the bootstrap script wrote. Re-applying is a no-op in production.
    const resolved = resolveApiLanguage();
    applyApiLanguage(resolved);
    setLanguage(resolved);
  }, []);

  function select(next: ApiLanguage) {
    const anchor = findAnchor();
    const before = anchor?.getBoundingClientRect().top;

    applyApiLanguage(next);
    setLanguage(next);

    // Measuring again flushes layout, so this reads the heights after the swap.
    if (anchor && before !== undefined) {
      const after = anchor.getBoundingClientRect().top;
      if (after !== before) {
        window.scrollBy({ top: after - before, behavior: 'instant' });
      }
    }

    const url = new URL(window.location.href);
    url.searchParams.set(apiLanguageQueryKey, next);
    window.history.replaceState(window.history.state, '', url);
  }

  return (
    <div className="api-language-bar">
      <span className="api-language-bar-label">Interface</span>
      <div
        className="api-language-switch"
        role="group"
        aria-label="Preferred interface"
      >
        {apiLanguages.map((option) => (
          <button
            key={option.value}
            type="button"
            data-api-language-option={option.value}
            aria-pressed={language === option.value}
            onClick={() => select(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}
