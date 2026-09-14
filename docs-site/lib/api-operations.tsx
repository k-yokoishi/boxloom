import type { ReactNode } from 'react';

export type ApiErrorRow = {
  status: number;
  code: string;
  meaning: ReactNode;
};

type ApiOperation = {
  description: ReactNode;
  errors?: ApiErrorRow[];
};

/** Returned by any operation, so each interface page lists these once. */
export const commonErrors: ApiErrorRow[] = [
  {
    status: 400,
    code: 'INVALID_REQUEST',
    meaning: 'A field or query parameter is missing, malformed, or out of range.',
  },
  {
    status: 401,
    code: 'UNAUTHORIZED',
    meaning: 'A Bearer token is required but was not supplied.',
  },
  {
    status: 403,
    code: 'FORBIDDEN',
    meaning: 'The supplied Bearer token is invalid.',
  },
  { status: 404, code: 'ROUTE_NOT_FOUND', meaning: 'No such route.' },
  {
    status: 405,
    code: 'METHOD_NOT_ALLOWED',
    meaning: 'The route does not accept this request method.',
  },
  {
    status: 413,
    code: 'REQUEST_TOO_LARGE',
    meaning: 'The request body exceeds 16 KiB.',
  },
  {
    status: 415,
    code: 'UNSUPPORTED_MEDIA_TYPE',
    meaning: (
      <>
        The <code>Content-Type</code> is not <code>application/json</code>.
      </>
    ),
  },
  {
    status: 500,
    code: 'INTERNAL_ERROR',
    meaning: 'The server could not complete the request.',
  },
  {
    status: 503,
    code: 'WORLD_NOT_LOADED',
    meaning: 'No Minecraft world is currently loaded.',
  },
  {
    status: 504,
    code: 'TIMEOUT',
    meaning: 'The Minecraft server thread did not respond before the deadline.',
  },
];

const invalidDimension: ApiErrorRow = {
  status: 400,
  code: 'INVALID_DIMENSION',
  meaning: 'The dimension is not a valid namespaced ID.',
};

const dimensionNotFound: ApiErrorRow = {
  status: 404,
  code: 'DIMENSION_NOT_FOUND',
  meaning: 'The requested dimension is not loaded.',
};

const invalidBlock: ApiErrorRow = {
  status: 400,
  code: 'INVALID_BLOCK',
  meaning: 'The block is not a valid namespaced ID, or is not a registered block.',
};

const playerNotConnected: ApiErrorRow = {
  status: 404,
  code: 'PLAYER_NOT_CONNECTED',
  meaning: 'No connected player has that username.',
};

/**
 * What every interface page says the same way: the prose describing an
 * operation and the errors it can return. Each page contributes only its own
 * signature, parameter tables, and examples.
 */
export const apiOperations = {
  say: {
    description: (
      <p>
        Broadcast a system message to every connected player, and report how many
        players received it.
      </p>
    ),
  },
  get_players: {
    description: (
      <p>
        List the players currently connected to the Minecraft server, in server
        list order.
      </p>
    ),
  },
  get_player_position: {
    description: (
      <p>Read a connected player&apos;s position, dimension, and look direction.</p>
    ),
    errors: [playerNotConnected],
  },
  teleport_player: {
    description: (
      <p>
        Teleport a connected player to absolute coordinates. Omitted dimension and
        look direction values are preserved.
      </p>
    ),
    errors: [
      invalidDimension,
      {
        status: 400,
        code: 'INVALID_POSITION',
        meaning: "The destination is outside Minecraft's spawnable bounds.",
      },
      playerNotConnected,
      {
        ...dimensionNotFound,
        meaning: 'The destination dimension is not loaded.',
      },
      {
        status: 409,
        code: 'TELEPORT_FAILED',
        meaning: 'Minecraft refused the teleport.',
      },
    ],
  },
  get_block: {
    description: (
      <p>
        Read one block in a loaded dimension. The result echoes the resolved
        dimension and position alongside the block that is there.
      </p>
    ),
    errors: [invalidDimension, dimensionNotFound],
  },
  set_block: {
    description: (
      <p>
        Place one block in a loaded dimension. The block is placed in its default
        state; block state properties are not configurable. <code>changed</code>{' '}
        reports whether the world actually changed, so placing a block that is
        already there reports no change.
      </p>
    ),
    errors: [invalidDimension, invalidBlock, dimensionNotFound],
  },
  fill: {
    description: (
      <p>
        Fill the inclusive cuboid between two block positions. The corners may be
        given in either order, and one call may cover at most 32,768 blocks. The
        result reports how many blocks actually changed.
      </p>
    ),
    errors: [
      {
        status: 400,
        code: 'INVALID_REQUEST',
        meaning: 'The region covers more than 32,768 blocks.',
      },
      invalidDimension,
      invalidBlock,
      {
        status: 400,
        code: 'INVALID_POSITION',
        meaning:
          "A corner is outside Minecraft's spawnable bounds or the dimension's build height.",
      },
      dimensionNotFound,
    ],
  },
  summon: {
    description: (
      <>
        <p>
          Summon one entity in a loaded dimension, optionally configured with NBT
          data.
        </p>
        <p>
          Supplying NBT skips Minecraft&apos;s natural spawn finalization, so
          equipment, variants, and similar randomized traits are not applied. Omit
          it to get an entity equivalent to a command-summoned one.
        </p>
      </>
    ),
    errors: [
      invalidDimension,
      {
        status: 400,
        code: 'INVALID_ENTITY',
        meaning:
          'The entity is not a valid namespaced ID, is not registered, or cannot be summoned.',
      },
      {
        status: 400,
        code: 'INVALID_POSITION',
        meaning: "The position is outside Minecraft's spawnable bounds.",
      },
      {
        status: 400,
        code: 'INVALID_ENTITY_NBT',
        meaning: 'The NBT is malformed or could not be applied to this entity type.',
      },
      {
        status: 400,
        code: 'ENTITY_NOT_ALLOWED_IN_PEACEFUL',
        meaning:
          'The world is on peaceful difficulty and this entity cannot spawn there.',
      },
      dimensionNotFound,
      {
        status: 409,
        code: 'DUPLICATE_ENTITY_UUID',
        meaning: 'An entity with the UUID supplied in the NBT already exists.',
      },
    ],
  },
  watch_chat: {
    description: (
      <p>
        Listen for player chat messages as one long-lived stream. With no cursor
        the stream starts at the current live position and does not replay chat
        that predates the connection. Reconnecting with the ID of the last event
        you processed replays retained events strictly after it; the server keeps
        the most recent 1,024 chat events, and cursors do not survive a Minecraft
        server restart.
      </p>
    ),
    errors: [
      {
        status: 400,
        code: 'INVALID_EVENT_CURSOR',
        meaning: 'The cursor is malformed or ahead of the live stream.',
      },
      {
        status: 406,
        code: 'NOT_ACCEPTABLE',
        meaning: (
          <>
            The <code>Accept</code> header does not allow{' '}
            <code>text/event-stream</code>.
          </>
        ),
      },
      {
        status: 410,
        code: 'EVENT_CURSOR_EXPIRED',
        meaning:
          'The cursor belongs to another server session, or its following events are no longer retained.',
      },
    ],
  },
} satisfies Record<string, ApiOperation>;

export type ApiOperationId = keyof typeof apiOperations;
