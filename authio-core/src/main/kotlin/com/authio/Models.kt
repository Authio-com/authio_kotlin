package com.authio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The verified Authio session held by an end-user.
 *
 * Multi-org-first: [userId] always identifies the person; [orgId] is the
 * active organization (may be `null` when the user has authenticated but
 * not yet selected an org).
 *
 * Wire format mirrors auth-core's `SessionEnvelope`. Field names use
 * snake_case via `@SerialName` so the same DTO survives round-tripping
 * over the network without a custom adapter.
 */
@Serializable
data class AuthioSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    /** RFC 3339 / ISO 8601 instant string. */
    @SerialName("expires_at") val expiresAt: String,
    val user: User? = null,
    @SerialName("active_organization") val activeOrganization: Organization? = null,
    @SerialName("active_role") val activeRole: String? = null,
    val memberships: List<MembershipWithOrg> = emptyList(),
) {
    val userId: String get() = user?.id ?: ""
    val orgId: String? get() = activeOrganization?.id
    val role: String? get() = activeRole
}

@Serializable
data class User(
    val id: String,
    @SerialName("project_id") val projectId: String = "",
    val email: String,
    @SerialName("email_verified") val emailVerified: Boolean = false,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("default_organization_id") val defaultOrganizationId: String? = null,
)

@Serializable
data class Organization(
    val id: String,
    @SerialName("project_id") val projectId: String = "",
    val name: String,
    val slug: String,
)

enum class MembershipStatus {
    @SerialName("invited") INVITED,
    @SerialName("active") ACTIVE,
    @SerialName("suspended") SUSPENDED,
    @SerialName("deactivated") DEACTIVATED,
}

@Serializable
data class Membership(
    val id: String,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("user_id") val userId: String,
    @SerialName("organization_id") val organizationId: String,
    val role: String,
    val status: MembershipStatus,
    @SerialName("preferred_login_method") val preferredLoginMethod: String? = null,
    @SerialName("invited_by") val invitedBy: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
)

/**
 * `/v1/me/organizations` returns Membership rows with the Organization
 * inlined; the wire shape is a Membership with an extra `organization`
 * field, so we model it as a flat composite type.
 */
@Serializable
data class MembershipWithOrg(
    val id: String,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("user_id") val userId: String,
    @SerialName("organization_id") val organizationId: String,
    val role: String,
    val status: MembershipStatus,
    @SerialName("preferred_login_method") val preferredLoginMethod: String? = null,
    val organization: Organization,
)

/** Identity providers the Authio OAuth registry knows about. */
enum class OAuthProvider(val id: String) {
    GOOGLE("google"),
    MICROSOFT("microsoft"),
    APPLE("apple"),
    GITHUB("github"),
    SLACK("slack"),
    GITLAB("gitlab"),
    LINKEDIN("linkedin");

    companion object {
        fun parse(s: String?): OAuthProvider? =
            entries.firstOrNull { it.id.equals(s, ignoreCase = true) }
    }
}
