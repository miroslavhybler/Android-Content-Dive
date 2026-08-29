package com.contentdive.compose

import androidx.compose.ui.text.AnnotatedString
import com.contentdive.api.AnchorRef
import com.contentdive.api.SearchFragment
import com.contentdive.api.SearchFragmentId
import com.contentdive.api.SearchFragmentKind
import com.contentdive.api.SearchItemId
import com.contentdive.api.SearchScope

/**
 * Creates a normal String-backed [SearchFragment] from this annotated visible text.
 *
 * The returned fragment stores [AnnotatedString.text] exactly. Styles, paragraph metadata, links,
 * tags, and custom annotations are deliberately discarded, and the original `AnnotatedString` is
 * not retained. For structured content, call this once per semantic block and provide a distinct
 * [anchor]; one large annotated value may remain one fragment and use engine-owned chunking.
 *
 * Fragment ownership, non-blank text, and positive finite weight are validated when the resulting
 * fragment is indexed through [com.contentdive.api.ContentIndexer]. HTML and Markdown conversion
 * are not provided by this adapter.
 *
 * @param id identifier unique within the owning projection.
 * @param itemId ID of the item that will own the fragment.
 * @param scope scope shared with the owning item.
 * @param kind semantic ranking role for the visible text.
 * @param weight positive finite emphasis within ContentDive's fixed ranking policy.
 * @param anchor optional application-owned location represented by this text block.
 * @return an ordinary String-backed fragment suitable for any ContentDive backend.
 */
public fun AnnotatedString.toSearchFragment(
    id: SearchFragmentId,
    itemId: SearchItemId,
    scope: SearchScope,
    kind: SearchFragmentKind = SearchFragmentKind.BODY,
    weight: Double = 1.0,
    anchor: AnchorRef? = null,
): SearchFragment = SearchFragment(
    id = id,
    itemId = itemId,
    scope = scope,
    text = text,
    kind = kind,
    weight = weight,
    anchor = anchor,
)
