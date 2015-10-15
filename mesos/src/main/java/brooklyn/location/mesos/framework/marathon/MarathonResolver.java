/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.location.mesos.framework.marathon;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationRegistry;
import org.apache.brooklyn.api.location.LocationResolver.EnableableLocationResolver;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.location.BasicLocationRegistry;
import org.apache.brooklyn.core.location.LocationPropertiesFromBrooklynProperties;
import org.apache.brooklyn.core.location.dynamic.DynamicLocation;
import org.apache.brooklyn.core.location.internal.LocationInternal;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.text.KeyValueParser;
import org.apache.brooklyn.util.text.Strings;

import brooklyn.entity.mesos.framework.marathon.MarathonFramework;

/**
 * Examples of valid specs:
 *   <ul>
 *     <li>marathon:frameworkId
 *     <li>marathon:(name=marathon-framework)
 *     <li>marathon:frameworkId:(name=framework-brooklyn-1234,displayName='Marathon')
 */
public class MarathonResolver implements EnableableLocationResolver {

    private static final Logger LOG = LoggerFactory.getLogger(MarathonResolver.class);

    public static final String MARATHON = "marathon";
    public static final Pattern PATTERN = Pattern.compile("("+MARATHON+"|"+MARATHON.toUpperCase()+")" + ":([a-zA-Z0-9]+)" + "(:\\((.*)\\))?$");
    public static final Set<String> ACCEPTABLE_ARGS = ImmutableSet.of("name", "displayName");

    public static final String MARATHON_FRAMEWORK_SPEC = MARATHON + ":%s";

    private ManagementContext managementContext;

    @Override
    public void init(ManagementContext managementContext) {
        this.managementContext = checkNotNull(managementContext, "managementContext");
    }

    @Override
    public String getPrefix() {
        return MARATHON;
    }

    @Override
    public Location newLocationFromString(Map locationFlags, String spec, LocationRegistry registry) {
        return newLocationFromString(spec, registry, registry.getProperties(), locationFlags);
    }

    protected Location newLocationFromString(String spec, LocationRegistry registry, Map properties, Map locationFlags) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Resolving location '" + spec + "' with flags " + Joiner.on(",").withKeyValueSeparator("=").join(locationFlags));
        }
        String namedLocation = (String) locationFlags.get(LocationInternal.NAMED_SPEC_NAME.getName());

        Matcher matcher = PATTERN.matcher(spec);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid location '"+spec+"'; must specify something like marathon:entityId or marathon:entityId:(name=abc)");
        }

        String argsPart = matcher.group(4);
        Map<String, String> argsMap = (argsPart != null) ? KeyValueParser.parseMap(argsPart) : Collections.<String,String>emptyMap();
        String displayNamePart = argsMap.get("displayName");
        String namePart = argsMap.get("name");

        if (!ACCEPTABLE_ARGS.containsAll(argsMap.keySet())) {
            Set<String> illegalArgs = Sets.difference(argsMap.keySet(), ACCEPTABLE_ARGS);
            throw new IllegalArgumentException("Invalid location '"+spec+"'; illegal args "+illegalArgs+"; acceptable args are "+ACCEPTABLE_ARGS);
        }
        if (argsMap.containsKey("displayName") && Strings.isEmpty(displayNamePart)) {
            throw new IllegalArgumentException("Invalid location '"+spec+"'; if displayName supplied then value must be non-empty");
        }
        if (argsMap.containsKey("name") && Strings.isEmpty(namePart)) {
            throw new IllegalArgumentException("Invalid location '"+spec+"'; if name supplied then value must be non-empty");
        }

        Map<String, Object> filteredProperties = new LocationPropertiesFromBrooklynProperties().getLocationProperties(MARATHON, namedLocation, properties);
        MutableMap<String, Object> flags = MutableMap.<String, Object>builder().putAll(filteredProperties).putAll(locationFlags).build();

        String frameworkId = matcher.group(2);
        if (Strings.isBlank(frameworkId)) {
            throw new IllegalArgumentException("Invalid location '"+spec+"'; Marathon framework entity id must be non-empty");
        }

        // Build the display name
        StringBuilder name = new StringBuilder();
        if (displayNamePart != null) {
            name.append(displayNamePart);
        } else {
            name.append("Marathon Framework");
        }
        final String displayName =  name.toString();

        // Build the location name
        name = new StringBuilder();
        if (namePart != null) {
            name.append(namePart);
        } else {
            name.append("marathon-");
            name.append(frameworkId);
        }
        final String locationName =  name.toString();
        MarathonFramework framework = (MarathonFramework) managementContext.getEntityManager().getEntity(frameworkId);
        Iterable<Location> managedLocations = managementContext.getLocationManager().getLocations();

        for (Location location : managedLocations) {
            if (location instanceof MarathonLocation) {
                if (((MarathonLocation) location).getOwner().getId().equals(frameworkId)) {
                    return location;
                }
            }
        }

        LocationSpec<MarathonLocation> locationSpec = LocationSpec.create(MarathonLocation.class)
                .configure(flags)
                .configure(DynamicLocation.OWNER, framework)
                .configure(LocationInternal.NAMED_SPEC_NAME, locationName)
                .displayName(displayName);
        return managementContext.getLocationManager().createLocation(locationSpec);
    }

    @Override
    public boolean accepts(String spec, LocationRegistry registry) {
        return BasicLocationRegistry.isResolverPrefixForSpec(this, spec, true);
    }

    @Override
    public boolean isEnabled() {
        return true;
//        return Iterables.tryFind(managementContext.getEntityManager().getEntities(), Predicates.instanceOf(MarathonFramework.class)).isPresent();
    }

}