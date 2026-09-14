import Link from 'next/link';
import { ArrowRight, Box, MessageSquareText, Terminal } from 'lucide-react';

export default function HomePage() {
  return (
    <div className="home-shell">
      <section className="home-hero">
        <div className="home-copy">
          <div className="status-pill">
            <span aria-hidden="true" />
            Early alpha
          </div>
          <h1>Build Minecraft worlds from Python.</h1>
          <p className="home-lede">
            boxloom connects a small Python SDK to a server-side Fabric mod, so
            code can read and change a running Minecraft world.
          </p>

          <div className="home-actions">
            <Link className="home-button home-button-primary" href="/docs/installation">
              Install boxloom
              <ArrowRight aria-hidden="true" size={16} strokeWidth={1.8} />
            </Link>
            <Link className="home-button home-button-secondary" href="/docs/apis/python">
              Browse APIs
            </Link>
          </div>

          <div className="connection-line" aria-label="How boxloom connects">
            <span>
              <Terminal aria-hidden="true" size={15} /> Python
            </span>
            <i aria-hidden="true" />
            <span>
              <Box aria-hidden="true" size={15} /> Fabric
            </span>
            <i aria-hidden="true" />
            <span>
              <MessageSquareText aria-hidden="true" size={15} /> Minecraft
            </span>
          </div>
        </div>

        <div className="code-window" aria-label="Python set block example">
          <div className="code-window-bar">
            <div className="window-dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
            <span>place_block.py</span>
          </div>
          <pre>
            <code>
              <span className="code-keyword">from</span>{' '}
              <span className="code-module">boxloom</span>{' '}
              <span className="code-keyword">import</span>{' '}
              <span className="code-function">set_block</span>
              {'\n\n'}
              <span className="code-function">set_block</span>(
              <span className="code-number">0</span>,{' '}
              <span className="code-number">100</span>,{' '}
              <span className="code-number">0</span>,{'\n'}
              {'    '}
              <span className="code-string">&quot;minecraft:diamond_block&quot;</span>,
              {'\n'})
            </code>
          </pre>
          <div className="code-window-foot">
            <span>Python 3.9+</span>
            <span>Minecraft 26.2</span>
          </div>
        </div>
      </section>
    </div>
  );
}
