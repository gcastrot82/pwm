/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2017 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.config.profile;

import com.novell.ldapchai.ChaiEntry;
import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.provider.ChaiProvider;
import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.config.PwmSetting;
import password.pwm.config.PwmSettingCategory;
import password.pwm.config.StoredValue;
import password.pwm.config.UserPermission;
import password.pwm.config.stored.StoredConfigurationImpl;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.ldap.LdapPermissionTester;
import password.pwm.svc.cache.CacheKey;
import password.pwm.svc.cache.CachePolicy;
import password.pwm.util.java.StringUtil;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.logging.PwmLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LdapProfile extends AbstractProfile implements Profile {
    private static final PwmLogger LOGGER = PwmLogger.forClass(LdapProfile.class);

    protected LdapProfile(final String identifier, final Map<PwmSetting, StoredValue> storedValueMap) {
        super(identifier, storedValueMap);
    }

    public static LdapProfile makeFromStoredConfiguration(final StoredConfigurationImpl storedConfiguration, final String profileID) {
        final Map<PwmSetting,StoredValue> valueMap = AbstractProfile.makeValueMap(storedConfiguration, profileID, PwmSettingCategory.LDAP_PROFILE);
        return new LdapProfile(profileID, valueMap);
    }

    public Map<String, String> getSelectableContexts(
            final PwmApplication pwmApplication
    )
            throws PwmUnrecoverableException
    {
        final List<String> rawValues = readSettingAsStringArray(PwmSetting.LDAP_LOGIN_CONTEXTS);
        final Map<String, String> configuredValues = StringUtil.convertStringListToNameValuePair(rawValues, ":::");
        final Map<String, String> canonicalValues = new LinkedHashMap<>();
        for (final String dn : configuredValues.keySet() ) {
            final String label = configuredValues.get(dn);
            final String canonicalDN = readCanonicalDN(pwmApplication, dn);
            canonicalValues.put(canonicalDN, label);
        }
        return Collections.unmodifiableMap(canonicalValues);
    }

    public List<String> getRootContexts(
            final PwmApplication pwmApplication
    )
            throws PwmUnrecoverableException
    {
        final List<String> rawValues = readSettingAsStringArray(PwmSetting.LDAP_CONTEXTLESS_ROOT);
        final List<String> canonicalValues = new ArrayList<>();
        for (final String dn : rawValues ) {
            final String canonicalDN = readCanonicalDN(pwmApplication, dn);
            canonicalValues.add(canonicalDN);
        }
        return Collections.unmodifiableList(canonicalValues);
    }

    @Override
    public String getDisplayName(final Locale locale) {
        final String displayName = readSettingAsLocalizedString(PwmSetting.LDAP_PROFILE_DISPLAY_NAME,locale);
        return displayName == null || displayName.length() < 1 ? identifier : displayName;
    }

    public String getUsernameAttribute() {
        final String configUsernameAttr = this.readSettingAsString(PwmSetting.LDAP_USERNAME_ATTRIBUTE);
        final String ldapNamingAttribute = this.readSettingAsString(PwmSetting.LDAP_NAMING_ATTRIBUTE);
        return configUsernameAttr != null && configUsernameAttr.length() > 0 ? configUsernameAttr : ldapNamingAttribute;
    }

    public ChaiProvider getProxyChaiProvider(final PwmApplication pwmApplication) throws PwmUnrecoverableException {
        return pwmApplication.getProxyChaiProvider(this.getIdentifier());
    }

    @Override
    public ProfileType profileType() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<UserPermission> getPermissionMatches() {
        throw new UnsupportedOperationException();
    }

    public String readCanonicalDN(
            final PwmApplication pwmApplication,
            final String dnValue
    )
            throws PwmUnrecoverableException
    {
        {
            final boolean doCanonicalDnResolve = Boolean.parseBoolean(pwmApplication.getConfig().readAppProperty(AppProperty.SECURITY_LDAP_RESOLVE_CANONICAL_DN));
            if (!doCanonicalDnResolve) {
                return dnValue;
            }
        }

        String canonicalValue = null;
        final CacheKey cacheKey = CacheKey.makeCacheKey(LdapPermissionTester.class, null, "canonicalDN-" + this.getIdentifier() + "-" + dnValue);
        {
            final String cachedDN = pwmApplication.getCacheService().get(cacheKey);
            if (cachedDN != null) {
                canonicalValue = cachedDN;
            }
        }

        if (canonicalValue == null) {
            try {
                final ChaiProvider chaiProvider = this.getProxyChaiProvider(pwmApplication);
                final ChaiEntry chaiEntry = ChaiFactory.createChaiEntry(dnValue, chaiProvider);
                canonicalValue = chaiEntry.readCanonicalDN();
                final long cacheSeconds = Long.parseLong(pwmApplication.getConfig().readAppProperty(AppProperty.SECURITY_LDAP_CANONICAL_CACHE_SECONDS));
                final CachePolicy cachePolicy = CachePolicy.makePolicyWithExpiration(new TimeDuration(cacheSeconds, TimeUnit.SECONDS));
                pwmApplication.getCacheService().put(cacheKey, cachePolicy, canonicalValue);
                LOGGER.trace("read and cached canonical ldap DN value for input '" + dnValue + "' as '" + canonicalValue + "'");
            } catch (ChaiUnavailableException | ChaiOperationException e) {
                LOGGER.error("error while reading canonicalDN for dn value '" + dnValue + "', error: " + e.getMessage());
                return dnValue;
            }
        }

        return canonicalValue;
    }
}
