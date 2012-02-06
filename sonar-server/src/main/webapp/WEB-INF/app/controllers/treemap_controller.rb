#
# Sonar, entreprise quality control tool.
# Copyright (C) 2008-2012 SonarSource
# mailto:contact AT sonarsource DOT com
#
# Sonar is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 3 of the License, or (at your option) any later version.
#
# Sonar is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public
# License along with Sonar; if not, write to the Free Software
# Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
#
class TreemapController < ApplicationController
  helper :metrics

  SECTION=Navigation::SECTION_HOME

  def index
    html_id = params[:id]
    bad_request('Missing required property: id') if html_id.blank?

    width = params[:width]
    bad_request('Missing required property: width') if width.blank?
    bad_request('Bad width') if width.to_i<=0

    height = params[:height]
    bad_request('Missing required property: height') if height.blank?
    bad_request('Bad height') if height.to_i<=0

    size_metric=Metric.by_key(params[:size_metric]||'lines')
    bad_request('Unknown metric: ' + params[:size_metric]) unless size_metric

    color_metric=(params[:color_metric].present? ? Metric.by_key(params[:color_metric]) : nil)

    resource = nil
    if params[:resource]
      resource = Project.by_key(params[:resource])
      bad_request('Unknown resource: ' + params[:resource]) unless resource
      access_denied unless has_role?(:user, resource)
    end

    treemap = Treemap2.new(html_id, size_metric, width.to_i, height.to_i, {
      :color_metric => color_metric,
      :root_snapshot => (resource ? resource.last_snapshot : nil),
      :period_index => params[:period_index].to_i,
      :browsable => true
    })

    render :update do |page|
      page.replace_html  "tm-#{html_id}", :partial => 'treemap', :object => treemap
      page.replace_html  "tm-gradient-#{html_id}", :partial => 'gradient', :locals => {:metric => color_metric}
    end
  end

end