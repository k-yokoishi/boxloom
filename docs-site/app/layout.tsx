import type { Metadata } from 'next';
import { Provider } from '@/components/provider';
import { apiLanguageBootstrap, defaultApiLanguage } from '@/lib/api-language';
import './global.css';

export const metadata: Metadata = {
  title: {
    default: 'boxloom',
    template: '%s · boxloom',
  },
  description: 'Control Minecraft Java Edition from Python with boxloom.',
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" data-api-language={defaultApiLanguage} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: apiLanguageBootstrap }} />
      </head>
      <body className="flex min-h-screen flex-col">
        <Provider>{children}</Provider>
      </body>
    </html>
  );
}
