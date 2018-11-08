package com.dangdang.config.face.controller;

import com.dangdang.config.face.entity.CommonResponse;
import com.dangdang.config.face.entity.PropertyItem;
import com.dangdang.config.face.entity.PropertyItemVO;
import com.dangdang.config.face.service.NodeService;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
public class IndexController {

    @Autowired
    private NodeService nodeService;

    private static final String ZIP = ".zip";
    private static final String PROPERTIES = ".properties";
    /**
     * 表示为配置项注释信息，加在版本号后面
     */
    private static final String COMMENT_SUFFIX = "$";

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexController.class);

    @RequestMapping(value = {"", "/config-web"}, method = RequestMethod.GET)
    public String index() {
        return "redirect:/version";
    }

    /**
     * 首页界面
     */
    @GetMapping(value = {"/version", "/version/{version:.+}"})
    public ModelAndView rootNode(@PathVariable(required = false) String version) {
        final String root = getRoot();

        //过滤掉配置项的注释信息，返回所有版本信息
        final List<String> versions = nodeService.listChildren(root)
                .stream().filter(e -> !e.endsWith(COMMENT_SUFFIX))
                .sorted(Comparator.comparing(String::toString).reversed())
                .collect(Collectors.toList());

        //如果没有指定要查询的版本信息，则返回列表的第一个版本信息为当前显示的版本信息
        final String theVersion = version != null ? version : Iterables.getFirst(versions, null);

        final ModelAndView mv = new ModelAndView("index");
        mv.addObject("root", root);
        mv.addObject("versions", versions);
        mv.addObject("theVersion", theVersion);

        //查询的版本在所有版本信息中，返回此版本的分组信息
        if (Iterables.contains(versions, theVersion)) {
            final List<String> groups = nodeService.listChildren(makePaths(root, theVersion))
                    .stream().sorted().collect(Collectors.toList());
            mv.addObject("groups", groups);
        }

        return mv;
    }

    /**
     * 查询对应版本下分组的配置项信息
     */
    @GetMapping(value = "/group/{version}/{group:.+}")
    public ModelAndView groupData(@PathVariable String version, @PathVariable String group) {
        final List<PropertyItemVO> items = getItems(getRoot(), version, group);

        final ModelAndView mv = new ModelAndView("data", "items", items);
        mv.addObject("version", version);
        mv.addObject("group", group);

        return mv;
    }

    /**
     * 组装成key、value、comment信息对应
     */
    private List<PropertyItemVO> getItems(String root, String version, String group) {
        List<PropertyItemVO> items = Lists.newArrayList();

        //key、value信息
        final List<PropertyItem> props = nodeService.findProperties(makePaths(root, version, group));
        //key、comment信息
        final List<PropertyItem> itemComment = nodeService.findProperties(makePaths(root, version + COMMENT_SUFFIX, group));
        //组装成key、value、comment信息对应
        if (props != null) {
            Map<String, String> comments = Maps.newHashMap();
            if (itemComment != null) {
                for (PropertyItem comment : itemComment) {
                    comments.put(comment.getName(), comment.getValue());
                }
            }

            for (PropertyItem propertyItem : props) {
                PropertyItemVO vo = new PropertyItemVO(propertyItem);
                vo.setComment(comments.get(propertyItem.getName()));
                items.add(vo);
            }

            Collections.sort(items);
        }
        return items;
    }

    /**
     * 创建分组
     */
    @PostMapping(value = "/group/{version:.+}")
    public ModelAndView createGroup(@PathVariable String version, String newGroup) {
        version = StringUtils.trim(version);
        newGroup = StringUtils.trim(newGroup.trim());

        final String root = getRoot();

        final String groupPath = makePaths(root, version, newGroup);

        nodeService.createProperty(groupPath);

        return new ModelAndView("redirect:/version/" + version);
    }

    /**
     * 创建版本
     */
    @PostMapping(value = "/version/{version:.+}")
    public @ResponseBody
    CommonResponse<Object> createVersion(@PathVariable String version, String fromVersion) {
        LOGGER.debug("Create version {} from {}", version, fromVersion);

        version = StringUtils.trim(version);
        //会从此节拷贝配置项信息
        fromVersion = StringUtils.trim(fromVersion);

        if (!Strings.isNullOrEmpty(version)) {
            final String root = getRoot();

            final String versionNode = makePaths(root, version);
            boolean suc = nodeService.createProperty(versionNode);

            if (suc) {
                nodeService.createProperty(versionNode + COMMENT_SUFFIX);
                if (!Strings.isNullOrEmpty(fromVersion)) {
                    final String fromVersionNode = makePaths(root, fromVersion);
                    cloneVersion(fromVersionNode, versionNode);
                    cloneVersion(fromVersionNode + COMMENT_SUFFIX, versionNode + COMMENT_SUFFIX);
                }

                return new CommonResponse<>(true, "/version/" + version, null);
            }
        }

        return new CommonResponse<>(false, null, "Invalid Args");
    }

    /**
     * 把sourceVersionPath节点的子节点信息拷贝至destinationVersionPath
     */
    private void cloneVersion(String sourceVersionPath, String destinationVersionPath) {
        List<String> sourceGroups = nodeService.listChildren(sourceVersionPath);
        if (sourceGroups != null) {
            for (String sourceGroup : sourceGroups) {
                String sourceGroupFullPath = makePaths(sourceVersionPath, sourceGroup);
                String destinationGroupFullPath = makePaths(destinationVersionPath, sourceGroup);

                nodeService.createProperty(destinationGroupFullPath, null);
                List<PropertyItem> sourceProperties = nodeService.findProperties(sourceGroupFullPath);
                if (sourceProperties != null) {
                    for (PropertyItem sourceProperty : sourceProperties) {
                        nodeService.createProperty(makePaths(destinationGroupFullPath, sourceProperty.getName()), sourceProperty.getValue());
                    }
                }
            }
        }
    }

    /**
     * 新增配置项信息
     */
    @PostMapping(value = "/prop")
    public @ResponseBody
    CommonResponse<Object> createProp(String version, String group, String key, String value, String comment) {
        LOGGER.debug("Create property version: {}, group: {}, key: {}, value: {}, comment: {}",
                version, group, key, value, comment);

        version = StringUtils.trim(version);
        group = StringUtils.trim(group);
        key = StringUtils.trim(key);
        value = StringUtils.trim(value);
        comment = StringUtils.trim(comment);

        final String root = getRoot();

        final String propPath = makePaths(root, version, group, key);
        final boolean suc = nodeService.createProperty(propPath, value);

        if (suc) {
            if (!Strings.isNullOrEmpty(comment)) {
                final String commentPath = makePaths(root, version + COMMENT_SUFFIX, group, key);
                nodeService.createProperty(commentPath, comment);
            }

            return new CommonResponse<>(true, null, null);
        }

        return new CommonResponse<>(false, null, "Server Error");

    }

    /**
     * 更新配置项信息
     */
    @PutMapping(value = "/prop")
    public @ResponseBody
    CommonResponse<Object> updateProp(String version, String group, String key, String value, String comment) {
        LOGGER.debug("Update property version: {}, group: {}, key: {}, value: {}, comment: {}",
                version, group, key, value, comment);

        version = StringUtils.trim(version);
        group = StringUtils.trim(group);
        key = StringUtils.trim(key);
        value = StringUtils.trim(value);
        comment = StringUtils.trim(comment);

        final String root = getRoot();

        final String propPath = makePaths(root, version, group, key);
        final String commentPath = makePaths(root, version + COMMENT_SUFFIX, group, key);

        nodeService.updateProperty(propPath, value);
        nodeService.updateProperty(commentPath, comment);

        return new CommonResponse<>(true, null, null);


    }

    /**
     * 删除配置项信息
     */
    @DeleteMapping(value = "/prop/{version}/{group}/{key:.+}")
    public @ResponseBody
    CommonResponse<Object> deleteProp(@PathVariable String version, @PathVariable String group, @PathVariable String key) {
        LOGGER.debug("Delete property version: {}, group: {}, key: {}", version, group, key);

        final String root = getRoot();

        final String propPath = makePaths(root, version, group, key);

        nodeService.deleteProperty(propPath);

        return new CommonResponse<>(true, null, null);

    }

    /**
     * 获取根目录
     */
    private String getRoot() {
        final UserDetails principal = (UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal.getUsername();
    }

    /**
     * 删除对应分组
     */
    @DeleteMapping(value = "/group/{version}/{group:.+}")
    public @ResponseBody
    CommonResponse<Object> deleteGroup(@PathVariable String version, @PathVariable String group) {
        LOGGER.debug("Delete group version: {}, group: {}", version, group);

        final String root = getRoot();
        final String versionPath = makePaths(root, version, group);

        nodeService.deleteProperty(versionPath);

        return new CommonResponse<>(true, null, null);
    }

    /**
     * 拼接路径
     */
    private String makePaths(String root, String first, String... others) {
        String path = ZKPaths.makePath(root, first);
        if (others != null) {
            for (String other : others) {
                path = ZKPaths.makePath(path, other);
            }
        }
        return path;
    }

    @GetMapping(value = {"/export/{version:.+}", "/export/{version}/{group:.+}"})
    public @ResponseBody
    HttpEntity<byte[]> exportData(@PathVariable String version, @PathVariable(required = false) String group) {
        final String root = getRoot();

        if (!Strings.isNullOrEmpty(group)) {
            //export group
            final List<PropertyItemVO> items = getItems(root, version, group);
            final List<String> lines = formatPropertyLines(root, version, group, items);

            byte[] document = Joiner.on("\r\n").join(lines).getBytes();
            HttpHeaders header = new HttpHeaders();
            header.setContentType(new MediaType("application", "properties"));
            header.set("Content-Disposition", "inline; filename=" + group + ".property");
            header.setContentLength(document.length);
            return new HttpEntity<>(document, header);
        } else {
            //export version
            final String versionPath = makePaths(root, version);
            List<String> groups = nodeService.listChildren(versionPath);
            if (groups != null && !groups.isEmpty()) {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try(ZipOutputStream zipOutputStream = new ZipOutputStream(out)) {
                        for (String groupName : groups) {
                            String groupPath = makePaths(versionPath, groupName);
                            String fileName = ZKPaths.getNodeFromPath(groupPath) + PROPERTIES;

                            List<PropertyItemVO> items = getItems(root, version, groupName);
                            List<String> lines = formatPropertyLines(root, version, groupName, items);
                            if (!lines.isEmpty()) {
                                ZipEntry zipEntry = new ZipEntry(fileName);
                                zipOutputStream.putNextEntry(zipEntry);
                                IOUtils.writeLines(lines, "\r\n", zipOutputStream, Charsets.UTF_8.displayName());
                                zipOutputStream.closeEntry();
                            }
                        }
                    }

                    byte[] document = out.toByteArray();
                    HttpHeaders header = new HttpHeaders();
                    header.setContentType(new MediaType("application", "zip"));
                    header.set("Content-Disposition", "inline; filename=" + StringUtils.replace(root, "/", "-") + ZIP);
                    header.setContentLength(document.length);
                    return new HttpEntity<>(document, header);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            return null;
        }
    }

    /**
     * 拼接配置项信息成list
     */
    private List<String> formatPropertyLines(String root, String version, String group, List<PropertyItemVO> items) {
        List<String> lines = Lists.newArrayList();
        lines.add(String.format("# Export from zookeeper configuration group: [%s] - [%s] - [%s].", root,
                version, group));
        lines.add("");
        for (PropertyItemVO item : items) {
            if (!Strings.isNullOrEmpty(item.getComment())) {
                lines.add("# " + item.getComment());
            }
            lines.add(item.getName() + "=" + item.getValue());
        }
        return lines;
    }

    /**
     * 导入
     */
    @PostMapping("/import/{version:.+}")
    public ModelAndView importData(@PathVariable String version, MultipartFile file){
        final String fileName = file.getOriginalFilename();
        LOGGER.info("Upload file : {}", fileName);
        try (InputStream in = file.getInputStream()) {
            if (fileName.endsWith(PROPERTIES)) {
                saveGroup(version, fileName, in);

            } else if (fileName.endsWith(ZIP)) {
                try (ZipArchiveInputStream input = new ZipArchiveInputStream(in)) {
                    ArchiveEntry nextEntry = null;
                    while ((nextEntry = input.getNextEntry()) != null) {
                        String entryName = nextEntry.getName();
                        saveGroup(version, entryName, input);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }

        return new ModelAndView("redirect:/version/" + version);
    }

    /**
     * 保存流中的配置项信息
     */
    private void saveGroup(@PathVariable String version, String fileName, InputStream in) throws IOException {
        List<PropertyItemVO> items = parseInputFile(in);
        if(!items.isEmpty()) {
            final String group = Files.getNameWithoutExtension(fileName);
            final String dataPath = makePaths(getRoot(), version, group);
            final String commentPath = makePaths(getRoot(), version + COMMENT_SUFFIX, group);

            items.forEach(item -> {
                nodeService.createProperty(makePaths(dataPath, StringUtils.trim(item.getName())), StringUtils.trim(item.getValue()));
                nodeService.createProperty(makePaths(commentPath, StringUtils.trim(item.getName())), StringUtils.trim(item.getComment()));
            });
        }
    }


    /**
     * 以=号隔除至两段
     */
    private Splitter PROPERTY_SPLITTER = Splitter.on('=').limit(2);


    /**
     * 解析流中配置项信息，填充成list
     */
    private List<PropertyItemVO> parseInputFile(InputStream inputstream) throws IOException {
        //获取流中配置项信息
        List<String> lines = IOUtils.readLines(inputstream, Charsets.UTF_8.name());
        List<PropertyItemVO> items = Lists.newArrayList();
        String previousLine = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if(i == 0 && line.startsWith("# Export from zookeeper")) {
                continue;
            }

            //排除注释
            if (!line.startsWith("#")) {
                //以=号隔除至两段
                Iterable<String> parts = PROPERTY_SPLITTER.split(line);
                if (Iterables.size(parts) == 2) {
                    PropertyItemVO item = new PropertyItemVO(Iterables.getFirst(parts, null).trim(), Iterables.getLast(parts).trim());
                    if (previousLine != null && previousLine.startsWith("#")) {
                        item.setComment(org.springframework.util.StringUtils.trimLeadingCharacter(previousLine, '#').trim());
                    }
                    items.add(item);
                }
            }

            previousLine = line;
        }
        return items;
    }

}
