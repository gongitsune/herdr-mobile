package io.herdrmobile.android.ui

import io.herdrmobile.core.workspace.Pane
import io.herdrmobile.core.workspace.Space
import io.herdrmobile.core.workspace.Tab

internal fun Tab.openingPane(): Pane? = panes.firstOrNull { it.agent != null } ?: panes.firstOrNull()

/** Keep tab order authoritative; empty tabs cannot supply a terminal to open. */
internal fun Space.openingPane(): Pane? = tabs.firstNotNullOfOrNull { it.openingPane() }
