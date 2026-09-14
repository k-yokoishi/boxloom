import defaultMdxComponents from 'fumadocs-ui/mdx';
import type { ApiErrorRow } from '@/lib/api-operations';

const Table = defaultMdxComponents.table;

export function ErrorTable({ errors }: { errors: ApiErrorRow[] }) {
  return (
    <Table>
      <thead>
        <tr>
          <th>Status</th>
          <th>Code</th>
          <th>Meaning</th>
        </tr>
      </thead>
      <tbody>
        {errors.map((error) => (
          <tr key={`${error.status}-${error.code}`}>
            <td>{error.status}</td>
            <td>
              <code>{error.code}</code>
            </td>
            <td>{error.meaning}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}
