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

const invalidClock: ApiErrorRow = {
  status: 400,
  code: 'INVALID_CLOCK',
  meaning: 'The clock is not a valid world clock ID.',
};

const clockNotFound: ApiErrorRow = {
  status: 404,
  code: 'CLOCK_NOT_FOUND',
  meaning: 'The requested world clock does not exist.',
};

const eventStreamErrors: ApiErrorRow[] = [
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
];

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
  get_player_location: {
    description: (
      <p>Read a connected player&apos;s position, dimension, and look direction.</p>
    ),
    errors: [playerNotConnected],
  },
  get_targeted_block: {
    description: (
      <p>
        Read the first block under the server-side crosshair within the player&apos;s
        block interaction reach. Entities occlude blocks, and fluids are ignored.
        No target is a successful null result.
      </p>
    ),
    errors: [playerNotConnected],
  },
  attack: {
    description: (
      <p>
        Attack the targeted block or entity once, or swing at empty space. Native
        game-mode, tool, attack reach, damage cooldown and protection rules apply.
        Any API mining session ends before attacking; API item use is cancelled without release effects.
        The arm swing is sent to the player and observers.
      </p>
    ),
    errors: [
      playerNotConnected,
      { status: 409, code: 'PLAYER_CANNOT_ATTACK', meaning: 'The player is dead, spectating, using an item, or unable to attack with their held item.' },
      { status: 409, code: 'UNSUPPORTED_ATTACK_TARGET', meaning: 'The held item is a piercing weapon, which uses a separate native attack path.' },
    ],
  },
  use: {
    description: (
      <p>
        Use the main hand once on the aimed block or entity, or in the air.
        Native item effects, consumption, creative rules, cooldowns, reach,
        spawn protection and Fabric cancellation apply. Cancels API mining and continuous item use.
        There is no automatic offhand fallback.
      </p>
    ),
    errors: [
      playerNotConnected,
      { status: 409, code: 'PLAYER_CANNOT_USE', meaning: 'The player is dead, spectating, already using an item, or otherwise unable to interact with their held item.' },
    ],
  },
  start_using_item: {
    description: (
      <p>
        Start using the main-hand item itself and keep using it until native
        completion or API release. Food and drink complete once. Native hunger,
        cooldowns, creative rules and Fabric cancellation apply. Repeating start
        while active preserves progress. API mining is cancelled.
      </p>
    ),
    errors: [
      playerNotConnected,
      { status: 409, code: 'PLAYER_CANNOT_USE', meaning: 'The player is dead, spectating, disconnected, already using a non-API item, or otherwise unable to use the held item.' },
      { status: 409, code: 'ITEM_NOT_CONTINUOUSLY_USABLE', meaning: 'The main hand is empty or its item has no continuous use.' },
    ],
  },
  stop_using_item: {
    description: (
      <p>
        Release API-owned item use. Interrupt food or drink, or release a charged
        bow using native firing rules. Inactive or completed sessions are a no-op.
        Native client-owned use is left unchanged.
      </p>
    ),
    errors: [playerNotConnected],
  },
  start_mining: {
    description: (
      <p>
        Enable held mining and return immediately. Wait without a target, mine the
        aimed block and continue after each break until stopped. Native tool speed,
        drops, durability and protection callbacks apply. Repeating start preserves
        progress.
      </p>
    ),
    errors: [
      playerNotConnected,
      { status: 409, code: 'PLAYER_CANNOT_ATTACK', meaning: 'The player cannot start attacking in their current state.' },
      { status: 409, code: 'UNSUPPORTED_ATTACK_TARGET', meaning: 'The held item is a piercing weapon.' },
    ],
  },
  stop_mining: {
    description: (
      <p>
        Cancel the player&apos;s API mining session and clear its crack animation.
        Pending native delayed completion is cancelled too. An inactive session is
        a successful no-op.
      </p>
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
  give_item: {
    description: (
      <p>
        Give a connected player an item using vanilla <code>/give</code> rules.
        Matching stacks and empty inventory slots are used automatically without
        replacing other items or changing the selected hotbar slot. Blocks with
        an item form use item IDs too. Survival overflow drops at the player with
        immediate pickup reserved for them; creative overflow may be discarded,
        as in vanilla. The returned count is the amount given, rather than the
        amount retained in inventory. Player operator permissions are not required.
        The response reports the added count in each destination slot and a
        selectable hotbar slot when one received items.
      </p>
    ),
    errors: [
      playerNotConnected,
      {
        status: 400,
        code: 'INVALID_ITEM',
        meaning: 'The item ID is malformed, unknown, air, or disabled in this world.',
      },
      {
        status: 400,
        code: 'INVALID_COUNT',
        meaning: 'The count exceeds 100 stacks of this item.',
      },
    ],
  },
  select_slot: {
    description: (
      <p>
        Select a connected player&apos;s hotbar slot, numbered 0–8 from left to
        right (keyboard keys 1–9), and synchronize the selection to their client.
        Empty slots may be selected. Items in the main inventory or offhand are
        not moved. Changing slots stops API mining and main-hand item use
        without releasing a bow shot; selecting the current slot preserves
        ongoing actions. Player operator permissions are not required.
      </p>
    ),
    errors: [playerNotConnected],
  },
  set_player_gamemode: {
    description: (
      <p>
        Set a connected player&apos;s game mode to <code>creative</code> or{' '}
        <code>survival</code>. Setting the current mode succeeds too. The result
        reports the mode immediately after the operation. Uses the same API
        authentication as other operations; the player does not need operator
        permissions. The world&apos;s default game mode is preserved.
      </p>
    ),
    errors: [
      playerNotConnected,
      {
        status: 409,
        code: 'GAME_MODE_CHANGE_FAILED',
        meaning: 'Minecraft could not apply the requested game mode.',
      },
    ],
  },
  get_block: {
    description: (
      <p>
        Read one block in a loaded dimension. The result echoes the resolved
        dimension and position alongside the block and its complete state properties.
      </p>
    ),
    errors: [invalidDimension, dimensionNotFound],
  },
  set_block: {
    description: (
      <p>
        Place one block in a loaded dimension, starting from its default state
        and applying optional string properties. The response describes the actual
        block state after placement. <code>changed</code> includes changes to
        properties alone; repeating an identical state reports no change.
      </p>
    ),
    errors: [
      invalidDimension, invalidBlock, dimensionNotFound,
      { status: 400, code: 'INVALID_BLOCK_PROPERTY', meaning: 'A property does not exist on this block, or its string value is invalid. The world is not changed.' },
    ],
  },
  fill: {
    description: (
      <p>
        Fill the inclusive cuboid between two block positions. The corners may be
        given in either order, and one call may cover at most 32,768 blocks. The
        result counts positions placed or destroyed once each. Choose replace,
        destroy, keep, outline, hollow, or strict, and optionally filter the old
        block ID and properties. Destination properties start from block defaults.
      </p>
    ),
    errors: [
      {
        status: 400,
        code: 'INVALID_REQUEST',
        meaning: 'The region covers more than 32,768 blocks, a mode is invalid, keep is combined with replace, or replaceProperties has no replace block.',
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
      { status: 400, code: 'INVALID_BLOCK_PROPERTY', meaning: 'A destination or filter property is unknown or has an invalid string value. The world is not changed.' },
    ],
  },
  get_entities: {
    description: (
      <>
        <p>
          List loaded, alive entities in one dimension, including players and
          dropped items. The result is a snapshot captured on the server thread;
          no chunks are loaded.
        </p>
        <p>
          Filter by exact entity type and an optional inclusive sphere, then sort,
          then apply the limit. Omit the type for all types. Supply all three
          center coordinates for a radius or distance sorting. Distance ties use
          UUID order; arbitrary order is unspecified. No matches returns an empty
          list. Health is null for entities without health.
        </p>
      </>
    ),
    errors: [
      invalidDimension,
      {
        status: 400,
        code: 'INVALID_ENTITY',
        meaning: 'The entity type ID is malformed or not registered.',
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
  kill_entity: {
    description: (
      <p>
        Kill one entity by UUID in a loaded dimension, using Minecraft&apos;s
        normal kill processing. An absent, unloaded, or already dead entity
        reports zero. This operation does not load chunks.
      </p>
    ),
    errors: [invalidDimension, dimensionNotFound],
  },
  kill_entities: {
    description: (
      <>
        <p>
          Kill loaded entities with an exact entity type ID. Supply absolute
          coordinates and a radius together to select an inclusive sphere around
          that position, measured from each entity&apos;s position. Omit the
          range fields to select that type throughout the dimension.
        </p>
        <p>
          No chunks are loaded. An empty selection reports zero. The selection is
          captured before killing starts, so new drops or smaller mobs spawned
          during death processing are not added to it.
        </p>
      </>
    ),
    errors: [
      invalidDimension,
      {
        status: 400,
        code: 'INVALID_ENTITY',
        meaning: 'The entity type ID is malformed or not registered.',
      },
      dimensionNotFound,
    ],
  },
  get_time: {
    description: (
      <p>
        Read a world clock&apos;s total elapsed ticks. Clocks are shared across
        dimensions that use them. The result is a snapshot; a running clock
        continues advancing after the response.
      </p>
    ),
    errors: [invalidClock, clockNotFound],
  },
  set_time: {
    description: (
      <>
        <p>
          Set a world clock&apos;s total elapsed ticks, or advance to the next
          occurrence of a time marker. The built-in Overworld markers are{' '}
          <code>day</code>, <code>noon</code>, <code>night</code>, and{' '}
          <code>midnight</code>; custom marker IDs are also supported.
        </p>
        <p>
          This follows Minecraft 26.3&apos;s <code>/time set</code> behavior: a
          marker advances the clock, while an integer replaces its total ticks.
          The result reports total ticks immediately after the change. Game time,
          the clock&apos;s paused state, and its rate are preserved.
        </p>
      </>
    ),
    errors: [
      invalidClock,
      {
        status: 400,
        code: 'INVALID_TIME_MARKER',
        meaning: 'The time marker is not a valid ID.',
      },
      clockNotFound,
      {
        status: 404,
        code: 'TIME_MARKER_NOT_FOUND',
        meaning: 'The requested time marker does not exist for this clock.',
      },
      {
        status: 409,
        code: 'TIME_ALREADY_AT_MARKER',
        meaning: 'The clock is already at the requested time marker.',
      },
    ],
  },
  add_time: {
    description: (
      <p>
        Add signed ticks to a world clock, matching Minecraft 26.3&apos;s{' '}
        <code>/time add</code>. Negative values rewind the clock; Minecraft clamps
        the result at zero. The result reports total ticks immediately after the
        change. Game time, the clock&apos;s paused state, and its rate are
        preserved.
      </p>
    ),
    errors: [invalidClock, clockNotFound],
  },
  watch_event: {
    description: (
      <p>
        Listen for player events through one long-lived stream, distinguishing
        chat and deaths by their type. With no cursor the stream starts at the
        current live position. Reconnecting with the ID of the last processed
        event replays retained events strictly after it. The server keeps the
        most recent 1,024 events across all types, and cursors do not survive a
        Minecraft server restart.
      </p>
    ),
    errors: eventStreamErrors,
  },
  subscribe_event: {
    description: (
      <p>
        Receive player events through serial callbacks on a background thread
        while the calling thread continues running game logic. Registration waits
        for the event stream to confirm readiness. The subscription exposes
        shutdown, completion, failure, and processed cursor controls.
      </p>
    ),
    errors: eventStreamErrors,
  },
  watch_chat: {
    description: (
      <p>
        Listen for player chat messages as one long-lived stream. With no cursor
        the stream starts at the current live position and does not replay chat
        that predates the connection. Reconnecting with the ID of the last event
        you processed replays retained events strictly after it; the server keeps
        the most recent 1,024 events across all types, and cursors do not survive
        a Minecraft server restart.
      </p>
    ),
    errors: eventStreamErrors,
  },
} satisfies Record<string, ApiOperation>;

export type ApiOperationId = keyof typeof apiOperations;
