package com.freesia.system.iam.internal.menu.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Route(UUID id, String name, String path, String component, String redirect,
                    Map<String, Object> meta, List<Route> children) {}
