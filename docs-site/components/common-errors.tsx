import { commonErrors } from '@/lib/api-operations';
import { ErrorTable } from './error-table';

export function CommonErrors() {
  return <ErrorTable errors={commonErrors} />;
}
