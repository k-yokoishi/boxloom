import defaultMdxComponents from 'fumadocs-ui/mdx';
import type { MDXComponents } from 'mdx/types';
import { ApiEndpoint } from './api-endpoint';
import { ApiLanguageBar } from './api-language-bar';
import { ApiVariant } from './api-variant';

export function getMDXComponents(components?: MDXComponents) {
  return {
    ...defaultMdxComponents,
    ApiEndpoint,
    ApiLanguageBar,
    ApiVariant,
    ...components,
  } satisfies MDXComponents;
}

export const useMDXComponents = getMDXComponents;

declare global {
  type MDXProvidedComponents = ReturnType<typeof getMDXComponents>;
}
