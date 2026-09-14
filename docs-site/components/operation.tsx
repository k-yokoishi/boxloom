import type { ReactNode } from 'react';
import { apiOperations, type ApiOperationId } from '@/lib/api-operations';
import { ErrorTable } from './error-table';

type OperationProps = {
  id: ApiOperationId;
  children: ReactNode;
};

/**
 * Wraps one operation on an interface page. The description and the error table
 * come from `lib/api-operations`, so every interface page describes an operation
 * the same way and only contributes its own signature, tables, and examples.
 *
 * The `## ` heading stays in MDX: the table of contents is built from the MDX
 * headings, and a heading rendered here would not appear in it.
 */
export function Operation({ id, children }: OperationProps) {
  const operation = apiOperations[id];

  if (!operation) {
    throw new Error(`Unknown API operation '${id}'`);
  }

  const errors = 'errors' in operation ? operation.errors : undefined;

  return (
    <>
      {operation.description}
      {children}
      {errors ? (
        <>
          <p>
            <strong>Errors</strong>
          </p>
          <ErrorTable errors={errors} />
        </>
      ) : null}
    </>
  );
}
