import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { gitConfig } from './shared';

export function baseOptions({ showSectionLinks = true } = {}): BaseLayoutProps {
  return {
    nav: {
      title: (
        <span className="brand-lockup">
          <img
            alt="boxloom"
            className="brand-logo"
            src={`${process.env.DOCS_BASE_PATH ?? ''}/boxloom-logo.svg`}
          />
        </span>
      ),
      url: '/',
      transparentMode: 'none',
    },
    links: showSectionLinks
      ? [
          {
            text: 'Installation',
            url: '/docs/installation',
            active: 'nested-url',
          },
          {
            text: 'APIs',
            url: '/docs/apis',
            active: 'nested-url',
          },
        ]
      : [],
    githubUrl: `https://github.com/${gitConfig.user}/${gitConfig.repo}`,
    searchToggle: {
      enabled: false,
    },
  };
}
