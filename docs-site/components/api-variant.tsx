import type { ReactNode } from 'react';
import type { ApiLanguage } from '@/lib/api-language';

type ApiVariantProps = {
  lang: ApiLanguage;
  children: ReactNode;
};

/**
 * One interface's example for an operation. Both variants are always rendered;
 * `html[data-api-language]` decides which one is displayed, so switching costs
 * a repaint instead of a re-render.
 */
export function ApiVariant({ lang, children }: ApiVariantProps) {
  return <div data-api-variant={lang}>{children}</div>;
}
